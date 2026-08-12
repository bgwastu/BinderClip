import Foundation
import Darwin

/// Fixed-port TCP control channel. Session remains responsible for authentication.
final class TcpControlTransport {
    static let port: UInt16 = 39421
    private let queue = DispatchQueue(label: "net.wastu.clipboard.tcp-control")
    private var listenerFD: Int32 = -1
    private var closed = false
    var onConnection: ((InputStream, OutputStream) -> Void)?
    var onOutgoingConnection: ((InputStream, OutputStream) -> Void)?

    func start() {
        queue.async { [weak self] in
            guard let self, !self.closed else { return }
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            guard fd >= 0 else { return }
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
            var address = sockaddr_in()
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = Self.port.bigEndian
            address.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)
            withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
            guard listen(fd, 8) == 0 else { Darwin.close(fd); return }
            self.listenerFD = fd
            while !self.closed {
                let client = accept(fd, nil, nil)
                guard client >= 0 else { break }
                self.makeStreams(client)
            }
        }
    }

    func connect(addresses: [String]) {
        queue.async { [weak self] in
            guard let self, !self.closed else { return }
            for host in addresses {
                let fd = socket(AF_INET, SOCK_STREAM, 0)
                guard fd >= 0 else { continue }
                let flags = fcntl(fd, F_GETFL, 0)
                _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
                var address = sockaddr_in()
                address.sin_family = sa_family_t(AF_INET)
                address.sin_port = Self.port.bigEndian
                guard inet_pton(AF_INET, host, &address.sin_addr) == 1 else { Darwin.close(fd); continue }
                let result = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
                if result == 0 { self.makeStreams(fd, outgoing: true); return }
                guard errno == EINPROGRESS else { Darwin.close(fd); continue }
                var pollfd = Darwin.pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                guard Darwin.poll(&pollfd, 1, 1500) > 0,
                      pollfd.revents & Int16(POLLERR | POLLHUP) == 0 else {
                    Darwin.close(fd)
                    continue
                }
                var error: Int32 = 0
                var errorLength = socklen_t(MemoryLayout<Int32>.size)
                guard getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &errorLength) == 0, error == 0 else {
                    Darwin.close(fd)
                    continue
                }
                _ = fcntl(fd, F_SETFL, flags)
                self.makeStreams(fd, outgoing: true)
                return
            }
        }
    }

    private func makeStreams(_ fd: Int32, outgoing: Bool = false) {
        var input: Unmanaged<CFReadStream>?
        var output: Unmanaged<CFWriteStream>?
        CFStreamCreatePairWithSocket(nil, fd, &input, &output)
        guard let input, let output else { Darwin.close(fd); return }
        let inputStream = input.takeRetainedValue() as InputStream
        let outputStream = output.takeRetainedValue() as OutputStream
        if outgoing { onOutgoingConnection?(inputStream, outputStream) }
        else { onConnection?(inputStream, outputStream) }
    }

    func close() {
        queue.async { [weak self] in
            guard let self else { return }
            self.closed = true
            if self.listenerFD >= 0 { Darwin.close(self.listenerFD); self.listenerFD = -1 }
        }
    }
}
