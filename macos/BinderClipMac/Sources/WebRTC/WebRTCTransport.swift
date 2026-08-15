import Foundation
import WebRTC

/// Transport-level event surfaced to the UI so the app can render connection truth.
enum WebRTCTransportEvent: Equatable {
    case connecting(String)
    case connected(String)
    case disconnected(String)
    case receivedText(String)
    case receivedOpenURL(String)
    case receivedImage(Data, String)
    case receivedRoster(String)
    case transferStatus(String)
    case log(String)
}

/// A single WebRTC peer session established entirely through a QR-tango
/// handshake (no signaling server). Each session owns one RTCPeerConnection,
/// one reliable/ordered DataChannel, and the ICE/QR machinery to bring it up.
///
/// This is intentionally independent of DirectTransport so the existing
/// LAN/mesh TCP path keeps working unchanged; WebRTCTransport is the
/// WAN/different-network path and, because ICE prefers host candidates, also a
/// robust same-network/mesh path.
final class WebRTCTransport: NSObject {
    static let defaultSTUNServers = [
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
    ]
    static let defaultTURNServers = [
        ("turn:openrelay.metered.ca:80", "openrelayproject", "openrelayproject"),
        ("turn:openrelay.metered.ca:443", "openrelayproject", "openrelayproject"),
    ]
    static let dataChannelLabel = "binderclip-data"
    static let sessionTimeout: TimeInterval = 30

    private(set) var connectionState: RTCIceConnectionState = .new
    private(set) var dataChannel: RTCDataChannel?
    private(set) var remoteDeviceID: String?

    var onEvent: ((WebRTCTransportEvent) -> Void)?
    /// Called once ICE gathering completes and a local QR payload is ready,
    /// for transparent signaling over an existing control channel.
    var onLocalPayloadReady: ((Data) -> Void)?
    /// Called for each application-level frame received over the DataChannel.
    var onFrame: ((Data) -> Void)?
    /// Called when the DataChannel transitions to open.
    var onDataChannelOpen: (() -> Void)?
    /// Called with our local SDP offer once it is set locally (SDP-signaling path).
    var onLocalOffer: ((String) -> Void)?
    /// Called with our answer SDP once produced (SDP-signaling path).
    var onAnswerReady: ((String) -> Void)?
    /// Called when ICE gathering for the local description completes.
    var onIceComplete: (() -> Void)?

    private var receiveBuffer = Data()

    private let factory: RTCPeerConnectionFactory
    private var peerConnection: RTCPeerConnection?
    private var pendingLocalDescription: RTCSessionDescription?
    private var pendingRemote: WebRTCQRCodec.Packet?
    private var waitingForScannedPayload = false

    // Reconnect / ICE-restart bookkeeping (single peer).
    private var iceRestartTimer: Timer?

    // Serializes the SDP/ICE state machine.
    private let stateQueue = DispatchQueue(label: "net.wastu.binderclip.webrtc.state")

    override init() {
        let config = RTCPeerConnectionFactoryOptions()
        config.ignoreLoopbackNetworkAdapter = true
        factory = RTCPeerConnectionFactory()
        factory.setOptions(config)
        super.init()
    }

    // MARK: - QR payload generation

    /// Generate the local identity card: DTLS fingerprint + ICE candidates.
    /// Must run after a PeerConnection exists and ICE gathering has completed.
    func currentQRPayload() -> Data? {
        stateQueue.sync {
            guard let pc = peerConnection, pc.localDescription != nil else { return nil }
            return makeQRPayload(from: pc)
        }
    }

    private func makeQRPayload(from pc: RTCPeerConnection) -> Data? {
        guard let sdp = pc.localDescription?.sdp else { return nil }
        let fp = sdp.fingerprint()
        let uf = sdp.iceUfrag()
        let pw = sdp.icePwd()
        if let fp, let uf, let pw, let fingerprint = fingerprintFromHex(fp) {
            let candidates = pc.localDescription?.sdp.candidates() ?? []
            let packet = WebRTCQRCodec.Packet(
                fingerprint: fingerprint,
                ufrag: WebRTCQRCodec.urlSafeBase64Encode(uf.data(using: .utf8)!),
                pwd: WebRTCQRCodec.urlSafeBase64Encode(pw.data(using: .utf8)!),
                candidates: Array(candidates.prefix(WebRTCQRCodec.maxCandidates))
            )
            return try? WebRTCQRCodec.encode(packet)
        }
        DirectTransportDebug.log("makeQRPayload missing fields (localDescription present)")
        return nil
    }

    // MARK: - Session lifecycle

    /// Begin a fresh QR-tango session: create the PeerConnection, gather ICE
    /// candidates, and once gathering completes surface a QR payload via onEvent.
    func beginSession() {
        stateQueue.async { [weak self] in
            guard let self else { return }
            self.teardownPeerConnection()
            let pc = self.makePeerConnection()
            self.peerConnection = pc
            self.waitingForScannedPayload = false
            self.pendingRemote = nil
            self.pendingLocalDescription = nil

            let dc = pc.dataChannel(forLabel: Self.dataChannelLabel, configuration: RTCDataChannelConfiguration().apply {
                $0.isOrdered = true
                $0.isNegotiated = true
                $0.channelId = 0
            })
            guard let dc else {
                self.emit(.log("Could not create data channel"))
                return
            }
            self.attach(dataChannel: dc)
            self.dataChannel = dc

            pc.offer(for: Self.emptyConstraints()) { [weak self, weak pc] sdp, _ in
                guard let self, let pc, let sdp else { return }
                pc.setLocalDescription(sdp) { [weak self, weak pc] error in
                    guard let self, let pc else { return }
                    if error == nil {
                        self.gatherThenNotify(pc)
                    } else {
                        self.emit(.log("setLocalDescription failed: \(error?.localizedDescription ?? "unknown")"))
                    }
                }
            }
        }
    }

    /// Call when the user has scanned the remote QR payload.
    func processScannedPayload(_ payload: Data) {
        stateQueue.async { [weak self] in
            guard let self else { return }
            do {
                let remote = try WebRTCQRCodec.decode(payload)
                self.pendingRemote = remote
                self.completeTango(withRemote: remote)
            } catch {
                self.emit(.log("Invalid pairing code: \(error)"))
                self.emit(.connecting("Invalid pairing code"))
            }
        }
    }

    // MARK: - SDP-based signaling (robust path)

    /// Begin a session and, once our offer is set locally, expose it via
    /// [onLocalOffer]. Call [processRemoteOffer] with the peer's offer to
    /// produce an answer, or [processRemoteAnswer] when we already sent ours.
    /// When [createOffer] is false, we only create the PeerConnection + data
    /// channel and wait for a remote offer (answerer role).
    func beginSDPSession(createOffer: Bool = true) {
        stateQueue.async { [weak self] in
            guard let self else { return }
            self.teardownPeerConnection()
            let pc = self.makePeerConnection()
            self.peerConnection = pc
            self.pendingRemote = nil
            self.pendingLocalDescription = nil

            guard let dc = pc.dataChannel(forLabel: Self.dataChannelLabel, configuration: RTCDataChannelConfiguration().apply {
                $0.isOrdered = true
                $0.isNegotiated = true
                $0.channelId = 0
            }) else {
                self.emit(.log("Could not create data channel"))
                return
            }
            self.attach(dataChannel: dc)
            self.dataChannel = dc

            guard createOffer else { return }
            pc.offer(for: Self.emptyConstraints()) { [weak self, weak pc] sdp, error in
                guard let self, let pc, let sdp else {
                    self?.emit(.log("createOffer failed: \(error?.localizedDescription ?? "nil")"))
                    return
                }
                pc.setLocalDescription(sdp) { [weak self, weak pc] error in
                    guard let self, let pc else { return }
                    if error == nil {
                        // Wait for ICE gathering to finish so the offer carries
                        // candidates; then expose it.
                        self.waitForGathering(pc) {
                            let finalSDP = pc.localDescription?.sdp ?? sdp.sdp
                            self.onLocalOffer?(finalSDP)
                        }
                    } else {
                        self.emit(.log("setLocalDescription failed: \(error?.localizedDescription ?? "unknown")"))
                    }
                }
            }
        }
    }

    private func waitForGathering(_ pc: RTCPeerConnection, then: @escaping () -> Void) {
        if pc.iceGatheringState == .complete {
            then()
            return
        }
        // Poll briefly; the delegate also fires notifyQRReady but we need the
        // gathered SDP here.
        var attempts = 0
        let timer = Timer(timeInterval: 0.2, repeats: true) { timer in
            attempts += 1
            if pc.iceGatheringState == .complete || attempts > 40 {
                timer.invalidate()
                then()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
    }

    /// Set the remote offer and produce an answer.
    func processRemoteOffer(_ sdp: String) {
        stateQueue.async { [weak self] in
            guard let self, let pc = self.peerConnection else { return }
            let offer = RTCSessionDescription(type: .offer, sdp: sdp)
            pc.setRemoteDescription(offer) { [weak self, weak pc] error in
                guard let self, let pc else { return }
                if let error {
                    self.emit(.log("setRemoteDescription(offer) failed: \(error.localizedDescription)"))
                    return
                }
                pc.answer(for: Self.emptyConstraints()) { [weak self, weak pc] answer, error in
                    guard let self, let pc, let answer else {
                        self?.emit(.log("createAnswer failed: \(error?.localizedDescription ?? "nil")"))
                        return
                    }
                    pc.setLocalDescription(answer) { [weak self] error in
                        guard let self else { return }
                        if error == nil {
                            self.scheduleICEWatchdog()
                            self.onAnswerReady?(answer.sdp)
                        } else {
                            self.emit(.log("setLocalDescription(answer) failed: \(error?.localizedDescription ?? "?")"))
                        }
                    }
                }
            }
        }
    }

    /// Set the remote answer (we already sent our offer).
    func processRemoteAnswer(_ sdp: String) {
        stateQueue.async { [weak self] in
            guard let self, let pc = self.peerConnection else { return }
            let answer = RTCSessionDescription(type: .answer, sdp: sdp)
            pc.setRemoteDescription(answer) { [weak self] error in
                guard let self else { return }
                if let error {
                    self.emit(.log("setRemoteDescription(answer) failed: \(error.localizedDescription)"))
                    return
                }
                self.scheduleICEWatchdog()
            }
        }
    }

    private func completeTango(withRemote remote: WebRTCQRCodec.Packet) {
        guard let pc = peerConnection else { return }
        // The local card may not be ready if gathering hasn't finished; retry briefly.
        guard let local = makeQRPayload(from: pc) else {
            DirectTransportDebug.log("completeTango waiting for local card (localDescription=\(pc.localDescription != nil))")
            stateQueue.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                self?.completeTango(withRemote: remote)
            }
            return
        }
        guard let localPacket = try? WebRTCQRCodec.decode(local) else { return }
        let localFingerprint = localPacket.fingerprint
        let remoteFingerprint = remote.fingerprint

        let role: Role = localFingerprint.lexicographicallyPrecedes(remoteFingerprint) ? .answerer : .offerer
        emit(.connecting("Establishing secure link…"))
        #if DEBUG
        DirectTransportDebug.log("completeTango role=\(role) remoteCands=\(remote.candidates.count)")
        #endif
        applyRole(role, local: localPacket, remote: remote, pc: pc)
    }

    private enum Role { case offerer, answerer }

    private func applyRole(_ role: Role, local: WebRTCQRCodec.Packet, remote: WebRTCQRCodec.Packet, pc: RTCPeerConnection) {
        guard let localSDP = pc.localDescription?.sdp else { return }
        let localDescription = RTCSessionDescription(type: .offer, sdp: localSDP)
        pendingLocalDescription = localDescription

        switch role {
        case .offerer:
            // We already hold a valid local offer; reconstruct the remote answer.
            let answerSDP = Self.remoteAnswerSDP(remote: remote, setup: "active")
            let remoteAnswer = RTCSessionDescription(type: .answer, sdp: answerSDP)
            DirectTransportDebug.log("offerer: applying reconstructed answer, cands=\(remote.candidates.count)")
            setRemoteAndConnect(remoteAnswer, pc: pc)
        case .answerer:
            // Roll back the pending local offer, then adopt the remote offer.
            pc.setLocalDescription(RTCSessionDescription(type: .rollback, sdp: "")) { [weak self, weak pc] error in
                guard let self, let pc else { return }
                if let error {
                    DirectTransportDebug.log("rollback failed: \(error.localizedDescription)")
                    self.emit(.log("rollback failed: \(error.localizedDescription)"))
                    return
                }
                let remoteOffer = Self.remoteOfferSDP(remote: remote, setup: "actpass")
                let offer = RTCSessionDescription(type: .offer, sdp: remoteOffer)
                pc.setRemoteDescription(offer) { [weak self, weak pc] error in
                    guard let self, let pc else { return }
                    if let error {
                        DirectTransportDebug.log("setRemoteDescription failed: \(error.localizedDescription)")
                        self.emit(.log("setRemoteDescription failed: \(error.localizedDescription)"))
                        return
                    }
                    pc.answer(for: Self.emptyConstraints()) { [weak self, weak pc] answer, error in
                        guard let self, let pc, let answer else {
                            DirectTransportDebug.log("createAnswer returned nil/error")
                            return
                        }
                        pc.setLocalDescription(answer) { [weak self, weak pc] error in
                            guard let self, let pc else { return }
                            if error == nil {
                                self.setRemoteAndConnect(RTCSessionDescription(type: .answer, sdp: answer.sdp), pc: pc)
                            } else {
                                DirectTransportDebug.log("setLocalDescription answer failed: \(error?.localizedDescription ?? "?")")
                            }
                        }
                    }
                }
            }
        }
    }

    private func setRemoteAndConnect(_ remote: RTCSessionDescription, pc: RTCPeerConnection) {
        pc.setRemoteDescription(remote) { [weak self] error in
            guard let self else { return }
            if let error {
                self.emit(.log("setRemoteDescription failed: \(error.localizedDescription)"))
                return
            }
            self.scheduleICEWatchdog()
            self.emit(.connecting("Negotiating direct connection…"))
        }
    }

    private func gatherThenNotify(_ pc: RTCPeerConnection) {
        if pc.iceGatheringState == .complete {
            notifyQRReady(pc)
            return
        }
        // Delegate callback will fire notifyQRReady on completion; add a 5s safety.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self, weak pc] in
            guard let self, let pc else { return }
            if pc.iceGatheringState == .complete {
                self.notifyQRReady(pc)
            }
        }
    }

    private func notifyQRReady(_ pc: RTCPeerConnection) {
        if let payload = makeQRPayload(from: pc) {
            emit(.log("Pairing code ready"))
            emit(.receivedRoster(payload.base64EncodedString()))
            onLocalPayloadReady?(payload)
        }
    }

    // MARK: - Reconnect / ICE restart

    private func scheduleICEWatchdog() {
        iceRestartTimer?.invalidate()
        let timer = Timer(timeInterval: Self.sessionTimeout, repeats: false) { [weak self] _ in
            self?.maybeICEStart()
        }
        RunLoop.main.add(timer, forMode: .common)
        iceRestartTimer = timer
    }

    private func maybeICEStart() {
        stateQueue.async { [weak self] in
            guard let self, let pc = self.peerConnection else { return }
            if self.connectionState == .connected || self.connectionState == .completed { return }
            self.emit(.connecting("Reconnecting…"))
            self.restartICE(pc)
        }
    }

    private func restartICE(_ pc: RTCPeerConnection) {
        pc.restartIce()
        pc.offer(for: Self.emptyConstraints()) { [weak self, weak pc] sdp, _ in
            guard let self, let pc, let sdp else { return }
            pc.setLocalDescription(sdp) { [weak self] error in
                guard let self else { return }
                if error == nil {
                    self.scheduleICEWatchdog()
                }
            }
        }
    }

    // MARK: - PeerConnection factory

    private func makePeerConnection() -> RTCPeerConnection {
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = Self.iceServers()
        config.iceTransportPolicy = .all
        config.tcpCandidatePolicy = .enabled
        config.candidateNetworkPolicy = .all

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let pc = factory.peerConnection(with: config, constraints: constraints, delegate: self)!
        return pc
    }

    private static func iceServers() -> [RTCIceServer] {
        var servers: [RTCIceServer] = []
        for url in defaultSTUNServers {
            servers.append(RTCIceServer(urlStrings: [url]))
        }
        for (url, username, credential) in defaultTURNServers {
            servers.append(RTCIceServer(urlStrings: [url], username: username, credential: credential))
        }
        return servers
    }

    private static func emptyConstraints() -> RTCMediaConstraints {
        RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    }

    // MARK: - Send

    /// Send an application frame (length-prefixed encrypted JSON) over the
    /// DataChannel, mirroring the TCP frame format so both transports share the
    /// same message handling.
    func send(_ frame: Data) {
        stateQueue.async { [weak self] in
            guard let self, let dc = self.dataChannel, dc.readyState == .open else { return }
            dc.sendData(RTCDataBuffer(data: frame, isBinary: true))
        }
    }

    var isOpen: Bool {
        stateQueue.sync { dataChannel?.readyState == .open }
    }

    func close() {
        stateQueue.async { [weak self] in
            self?.teardownPeerConnection()
        }
    }

    private func teardownPeerConnection() {
        iceRestartTimer?.invalidate(); iceRestartTimer = nil
        dataChannel?.close(); dataChannel = nil
        peerConnection?.close(); peerConnection = nil
        pendingLocalDescription = nil
        pendingRemote = nil
        waitingForScannedPayload = false
        connectionState = .new
    }

    private func attach(dataChannel: RTCDataChannel) {
        dataChannel.delegate = self
    }

    private func emit(_ event: WebRTCTransportEvent) {
        DispatchQueue.main.async { [weak self] in self?.onEvent?(event) }
    }

    // MARK: - SDP reconstruction helpers

    private static func remoteOfferSDP(remote: WebRTCQRCodec.Packet, setup: String) -> String {
        let fingerprint = remote.fingerprint.map { String(format: "%02X", $0) }.joined(separator: ":")
        let candidateLines = remote.candidates.enumerated().map { index, candidate in
            candidateSDPLine(candidate, index: index + 1)
        }.joined(separator: "\n")
        let sessionID = remote.fingerprint.prefix(8).reduce(0 as UInt64) { ($0 << 8) | UInt64($1) }
        return """
        v=0
        o=- \(sessionID) 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        a=ice-ufrag:\(remote.ufrag)
        a=ice-pwd:\(remote.pwd)
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=ice-options:trickle
        a=fingerprint:sha-256 \(fingerprint)
        a=setup:\(setup)
        a=mid:0
        a=sctp-port:5000
        \(candidateLines)
        """
    }

    private static func remoteAnswerSDP(remote: WebRTCQRCodec.Packet, setup: String) -> String {
        // The answer mirrors the offer's media line but with setup:active.
        remoteOfferSDP(remote: remote, setup: setup)
    }

    private static func candidateSDPLine(_ candidate: WebRTCQRCodec.Candidate, index: Int) -> String {
        WebRTCQRCodec.candidateSDPLine(candidate, index: index, priority: priority(for: candidate))
    }

    private static func priority(for candidate: WebRTCQRCodec.Candidate) -> UInt32 {
        switch candidate.type {
        case .host: return 2_118_130_432 // host, UDP
        case .srflx: return 1_695_370_752
        }
    }
}

extension WebRTCTransport: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        connectionState = newState
        switch newState {
        case .connected, .completed:
            iceRestartTimer?.invalidate(); iceRestartTimer = nil
            emit(.connected("Connected"))
        case .disconnected:
            emit(.disconnected("Waiting for peer…"))
        case .failed:
            emit(.disconnected("Connection lost"))
            maybeICEStart()
        case .closed:
            emit(.disconnected("Closed"))
        default:
            break
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete {
            notifyQRReady(peerConnection)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        attach(dataChannel: dataChannel)
        self.dataChannel = dataChannel
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
}

extension WebRTCTransport: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .open {
            emit(.log("Secure channel open"))
            onDataChannelOpen?()
        }
    }

    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        receiveBuffer.append(buffer.data)
        do {
            while let frame = try FrameCodec.decode(from: &receiveBuffer) {
                onFrame?(frame)
            }
        } catch {
            emit(.log("Rejected WebRTC frame: \(error)"))
            receiveBuffer.removeAll()
        }
    }
}

// MARK: - SDP parsing helpers

private extension String {
    /// Extract `a=fingerprint:sha-256 <hex>` from an SDP blob.
    func fingerprint() -> String? {
        let pattern = #"a=fingerprint:sha-256 ([0-9A-Fa-f:]+)"#
        guard let range = range(of: pattern, options: .regularExpression) else { return nil }
        let line = String(self[range])
        return line.components(separatedBy: " ").last
    }

    func iceUfrag() -> String? {
        regexValue("a=ice-ufrag:")
    }

    func icePwd() -> String? {
        regexValue("a=ice-pwd:")
    }

    func candidates() -> [WebRTCQRCodec.Candidate] {
        // Regex-based so it works regardless of line-ending style.
        let pattern = #"a=candidate:([^\r\n]*)"#
        let matches = matches(of: pattern)
        var result: [WebRTCQRCodec.Candidate] = []
        for match in matches {
            if let candidate = Self.parseCandidate(Substring(match)) {
                result.append(candidate)
            }
        }
        return result
    }

    private func regexValue(_ prefix: String) -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: prefix)
        let pattern = "\(escaped)([^\\r\\n]*)"
        guard let range = range(of: pattern, options: .regularExpression) else { return nil }
        let value = String(self[range]).dropFirst(prefix.count)
        return String(value).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func matches(of pattern: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = self as NSString
        return regex.matches(in: self, range: NSRange(location: 0, length: ns.length)).compactMap {
            guard $0.range.location != NSNotFound else { return nil }
            return ns.substring(with: $0.range)
        }
    }

    private static func parseCandidate(_ line: Substring) -> WebRTCQRCodec.Candidate? {
        let parts = line.components(separatedBy: " ")
        // a=candidate:foundation comp-id transport priority ip port typ [raddr] [rport] [tcptype x]
        guard parts.count >= 8 else { return nil }
        let transport = parts[2]
        let ip = parts[4]
        guard let port = UInt16(parts[5]), let typeString = parts[7].split(separator: "\r").first else { return nil }

        let family: WebRTCQRCodec.Candidate.AddressFamily
        let address: Data
        if ip.contains(":") {
            family = .ipv6
            var bytes = Data()
            for group in ip.split(separator: ":") {
                if group.isEmpty {
                    bytes.append(Data(repeating: 0, count: 2))
                } else if let value = UInt16(group, radix: 16) {
                    bytes.append(UInt8(value >> 8)); bytes.append(UInt8(value & 0xFF))
                }
            }
            while bytes.count < 16 { bytes.append(0) }
            address = bytes.prefix(16)
        } else if ip.hasSuffix(".local") {
            family = .mdns
            address = Data()
        } else {
            family = .ipv4
            address = ip.split(separator: ".").compactMap { UInt8($0) }.reduce(into: Data()) { $0.append($1) }
        }
        return WebRTCQRCodec.Candidate(
            addressFamily: family,
            kind: transport == "tcp" ? .tcp : .udp,
            type: typeString == "srflx" ? .srflx : .host,
            tcpType: .passive,
            address: address,
            port: port
        )
    }
}

private func fingerprintFromHex(_ hex: String) -> Data? {
    let cleaned = hex.replacingOccurrences(of: ":", with: "")
    guard cleaned.count % 2 == 0 else { return nil }
    var data = Data()
    var index = cleaned.startIndex
    while index < cleaned.endIndex {
        let next = cleaned.index(index, offsetBy: 2)
        guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
        data.append(byte)
        index = next
    }
    return data
}

private extension RTCPeerConnectionFactoryOptions {
    @discardableResult
    func apply(_ block: (RTCPeerConnectionFactoryOptions) -> Void) -> RTCPeerConnectionFactoryOptions {
        block(self)
        return self
    }
}

private extension RTCDataChannelConfiguration {
    @discardableResult
    func apply(_ block: (RTCDataChannelConfiguration) -> Void) -> RTCDataChannelConfiguration {
        block(self)
        return self
    }
}
