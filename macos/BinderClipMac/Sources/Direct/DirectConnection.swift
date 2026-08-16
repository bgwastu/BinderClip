import Foundation
import Network

final class InboundImageTransfer {
    let id: UUID
    let wireID: String
    let mimeType: String
    let expectedBytes: Int
    let expectedHash: String
    var data = Data()
    var nextIndex = 0

    init(id: UUID, wireID: String, mimeType: String, expectedBytes: Int, expectedHash: String) {
        self.id = id
        self.wireID = wireID
        self.mimeType = mimeType
        self.expectedBytes = expectedBytes
        self.expectedHash = expectedHash
    }
}

final class OutboundImageTransfer {
    let image: ImagePayload
    var nextIndex = 0
    var acknowledgedIndex = -1

    init(_ image: ImagePayload) {
        self.image = image
    }
}

/// Encapsulates a single direct TCP connection with length-prefixed frame decoding,
/// lifecycle management, and image transfer state.
final class DirectConnection {
    let connection: NWConnection
    let queue: DispatchQueue

    var peerID: String?
    var pairKey: Data?
    var lastActivity = Date()
    var inboundImage: InboundImageTransfer?
    var outboundImage: OutboundImageTransfer?
    var outboundTimeout: DispatchWorkItem?

    var onFrame: ((Data, DirectConnection) -> Void)?
    var onStateChange: ((NWConnection.State, DirectConnection) -> Void)?
    var onClosed: ((DirectConnection) -> Void)?

    private var receiveBuffer = Data()
    private var isStarted = false
    private var isCancelled = false

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    var id: ObjectIdentifier {
        ObjectIdentifier(connection)
    }

    var resolvedEndpoint: DirectEndpoint {
        if let remote = connection.currentPath?.remoteEndpoint,
           case let .hostPort(host, port) = remote {
            var cleanHost = host.debugDescription
            if let percentIdx = cleanHost.firstIndex(of: "%") {
                cleanHost = String(cleanHost[..<percentIdx])
            }
            return DirectEndpoint(host: cleanHost, port: port.rawValue)
        }
        return DirectEndpoint(host: "unknown", port: DirectTransport.port)
    }

    func start(alreadyStarted: Bool = false, initialBuffer: Data = Data()) {
        guard !isStarted, !isCancelled else { return }
        isStarted = true
        self.receiveBuffer = initialBuffer
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            self.queue.async {
                guard !self.isCancelled else { return }
                self.onStateChange?(state, self)
                switch state {
                case .failed, .cancelled:
                    self.closeInternal()
                default:
                    break
                }
            }
        }
        if !alreadyStarted {
            connection.start(queue: queue)
        }
        receiveLoop()
    }

    func sendRaw(frame: Data, completion: ((Error?) -> Void)? = nil) {
        guard !isCancelled else {
            completion?(DirectCryptoError.malformed)
            return
        }
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.queue.async {
                if let error {
                    completion?(error)
                    self.closeInternal()
                } else {
                    completion?(nil)
                }
            }
        })
    }

    func sendEncrypted(_ message: [String: Any], key: Data, completion: ((Error?) -> Void)? = nil) {
        do {
            let sealed = try DirectCrypto.seal(message, key: key)
            let body = try JSONSerialization.data(withJSONObject: sealed, options: [.sortedKeys])
            let frame = try FrameCodec.encode(body)
            sendRaw(frame: frame, completion: completion)
        } catch {
            completion?(error)
        }
    }

    func cancel() {
        queue.async { [weak self] in
            self?.closeInternal()
        }
    }

    private func closeInternal() {
        guard !isCancelled else { return }
        isCancelled = true
        outboundTimeout?.cancel()
        outboundTimeout = nil
        inboundImage = nil
        outboundImage = nil
        connection.cancel()
        onClosed?(self)
    }

    private func receiveLoop() {
        guard !isCancelled else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: FrameCodec.maximumPayloadBytes + 4) { [weak self] data, _, complete, error in
            guard let self else { return }
            self.queue.async {
                guard !self.isCancelled else { return }
                if let data {
                    self.receiveBuffer.append(data)
                }
                do {
                    while let frame = try FrameCodec.decode(from: &self.receiveBuffer) {
                        self.lastActivity = Date()
                        self.onFrame?(frame, self)
                    }
                } catch {
                    self.closeInternal()
                    return
                }

                if complete || error != nil {
                    self.closeInternal()
                } else {
                    self.receiveLoop()
                }
            }
        }
    }
}
