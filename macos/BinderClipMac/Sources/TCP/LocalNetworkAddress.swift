import Foundation

enum LocalNetworkAddress {
    /// Returns every usable local IPv4 address, including VPN/mesh interfaces.
    static func getLocalIPv4Addresses() -> [String] {
        var addresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return [] }
        defer { freeifaddrs(ifaddr) }

        var current = ifaddr
        while let ifa = current {
            defer { current = ifa.pointee.ifa_next }
            guard ifa.pointee.ifa_addr != nil,
                  ifa.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }

            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                ifa.pointee.ifa_addr,
                socklen_t(ifa.pointee.ifa_addr.pointee.sa_len),
                &hostname, socklen_t(hostname.count),
                nil, 0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }
            let address = String(cString: hostname)
            guard !address.hasPrefix("127."), !address.hasPrefix("169.254.") else { continue }
            if !addresses.contains(address) { addresses.append(address) }
        }
        return addresses
    }

    /// Returns a preferred local IPv4 address or nil if no network is available.
    static func getLocalIPv4Address() -> String? {
        getLocalIPv4Addresses().first
    }
}
