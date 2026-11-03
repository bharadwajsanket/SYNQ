import Foundation
import Network
import CoreImage
import AppKit

public struct GuestPendingUpload: Identifiable, Equatable, Sendable {
    public enum Status: String, Sendable {
        case pending
        case accepted
        case rejected
    }

    public let id: UUID
    public let filename: String
    public let fileSize: Int64
    public let stagedURL: URL
    public let receivedAt: Date
    public var status: Status

    public init(
        id: UUID = UUID(),
        filename: String,
        fileSize: Int64,
        stagedURL: URL,
        receivedAt: Date = Date(),
        status: Status = .pending
    ) {
        self.id = id
        self.filename = filename
        self.fileSize = fileSize
        self.stagedURL = stagedURL
        self.receivedAt = receivedAt
        self.status = status
    }
}

public enum GuestTransferSecurity {
    /// Generates a cryptographically secure 32-character hexadecimal token.
    public static func generateSecureToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Sanitizes filename to prevent directory traversal and harmful characters.
    public static func sanitizeFilename(_ raw: String) -> String {
        var name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        name = name.replacingOccurrences(of: "\\", with: "/")
        // Strip path components
        name = (name as NSString).lastPathComponent
        // Remove path traversal and dangerous characters
        name = name.replacingOccurrences(of: "..", with: "")
        name = name.replacingOccurrences(of: "/", with: "")
        name = name.replacingOccurrences(of: "\\", with: "")
        name = name.replacingOccurrences(of: ":", with: "")
        name = name.replacingOccurrences(of: "\0", with: "")

        // Disallow hidden files
        while name.hasPrefix(".") {
            name = String(name.dropFirst())
        }

        name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty {
            let timestamp = Int(Date().timeIntervalSince1970)
            return "guest_upload_\(timestamp).bin"
        }

        // Limit filename length to 128 characters to avoid filesystem issues
        if name.count > 128 {
            let ext = (name as NSString).pathExtension
            let base = (name as NSString).deletingPathExtension
            let truncatedBase = String(base.prefix(120))
            return ext.isEmpty ? truncatedBase : "\(truncatedBase).\(ext)"
        }

        return name
    }

    /// Determines a collision-safe destination URL in the target directory.
    public static func uniqueDestinationURL(for filename: String, in directory: URL) -> URL {
        let fileManager = FileManager.default
        let baseDestination = directory.appendingPathComponent(filename)
        if !fileManager.fileExists(atPath: baseDestination.path) {
            return baseDestination
        }

        let baseName = (filename as NSString).deletingPathExtension
        let ext = (filename as NSString).pathExtension
        var counter = 1

        while true {
            let candidateName = ext.isEmpty ? "\(baseName) (\(counter))" : "\(baseName) (\(counter)).\(ext)"
            let candidateURL = directory.appendingPathComponent(candidateName)
            if !fileManager.fileExists(atPath: candidateURL.path) {
                return candidateURL
            }
            counter += 1
            if counter > 9999 {
                // Safety bound
                return directory.appendingPathComponent("\(UUID().uuidString)_\(filename)")
            }
        }
    }

    /// Resolves local Wi-Fi or primary IPv4 address.
    public static func getLocalIPAddress() -> String {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return "127.0.0.1" }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                            &hostname, socklen_t(hostname.count),
                            nil, socklen_t(0), NI_NUMERICHOST)
                let ip = String(cString: hostname)
                if !ip.starts(with: "127.") {
                    if name == "en0" || name == "en1" {
                        return ip
                    }
                    if address == nil {
                        address = ip
                    }
                }
            }
        }
        return address ?? "127.0.0.1"
    }

    /// Generates a QR Code NSImage using CoreImage.
    public static func generateQRCode(from string: String, size: CGSize = CGSize(width: 240, height: 240)) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(string.data(using: .utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let ciImage = filter.outputImage else { return nil }

        let scaleX = size.width / ciImage.extent.width
        let scaleY = size.height / ciImage.extent.height
        let transformedImage = ciImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

        let rep = NSCIImageRep(ciImage: transformedImage)
        let nsImage = NSImage(size: size)
        nsImage.addRepresentation(rep)
        return nsImage
    }
}

/// Lightweight HTTP server supporting GET /guest and POST /upload
final class GuestHTTPServer: @unchecked Sendable {
    private var listener: NWListener?
    private let port: UInt16
    private let token: String
    private let expiresAt: Date
    private let stagingDirectory: URL
    private let onUploadReceived: @Sendable (GuestPendingUpload) -> Void
    private let onFailure: @Sendable (String) -> Void
    private let queue = DispatchQueue(label: "dev.synq.guest.server", qos: .userInitiated)

    private var isExpired: Bool {
        Date() >= expiresAt
    }

    init(
        port: UInt16 = 42460,
        token: String,
        expiresAt: Date,
        stagingDirectory: URL,
        onFailure: @escaping @Sendable (String) -> Void = { _ in },
        onUploadReceived: @escaping @Sendable (GuestPendingUpload) -> Void
    ) {
        self.port = port
        self.token = token
        self.expiresAt = expiresAt
        self.stagingDirectory = stagingDirectory
        self.onFailure = onFailure
        self.onUploadReceived = onUploadReceived
    }

    func start() throws {
        let params = NWParameters.tcp
        let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port) ?? 42460)
        listener.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }
        listener.stateUpdateHandler = { [onFailure] state in
            if case let .failed(error) = state {
                NSLog("GuestHTTPServer listener error: %@", String(describing: error))
                onFailure(error.localizedDescription)
            }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: queue)
        readRequest(connection: connection, buffer: Data())
    }

    private func readRequest(connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else {
                connection.cancel()
                return
            }
            if let error = error {
                NSLog("GuestHTTPServer receive error: %@", String(describing: error))
                connection.cancel()
                return
            }

            var currentBuffer = buffer
            if let data = data {
                currentBuffer.append(data)
            }

            // Check if headers are complete
            if let headerEndRange = currentBuffer.range(of: Data("\r\n\r\n".utf8)) {
                let headerData = currentBuffer.subdata(in: 0..<headerEndRange.lowerBound)
                guard let headerString = String(data: headerData, encoding: .utf8) else {
                    self.sendResponse(connection: connection, status: "400 Bad Request", body: "Malformed headers")
                    return
                }

                let lines = headerString.components(separatedBy: "\r\n")
                guard let requestLine = lines.first else {
                    self.sendResponse(connection: connection, status: "400 Bad Request", body: "Invalid request")
                    return
                }

                let parts = requestLine.components(separatedBy: " ")
                guard parts.count >= 2 else {
                    self.sendResponse(connection: connection, status: "400 Bad Request", body: "Invalid request line")
                    return
                }

                let method = parts[0].uppercased()
                let path = parts[1]

                // Parse content-length
                var contentLength: Int?
                var contentType: String?
                for line in lines.dropFirst() {
                    let lower = line.lowercased()
                    if lower.hasPrefix("content-length:") {
                        let val = line.dropFirst("content-length:".count).trimmingCharacters(in: .whitespaces)
                        contentLength = Int(val)
                    } else if lower.hasPrefix("content-type:") {
                        contentType = line.dropFirst("content-type:".count).trimmingCharacters(in: .whitespaces)
                    }
                }

                let bodyStartIndex = headerEndRange.upperBound
                let existingBody = currentBuffer.subdata(in: bodyStartIndex..<currentBuffer.count)

                if method == "GET" {
                    self.handleGET(path: path, connection: connection)
                } else if method == "POST" {
                    if let requiredLength = contentLength {
                        if requiredLength > 500 * 1024 * 1024 { // 500 MB limit
                            self.sendResponse(connection: connection, status: "413 Payload Too Large", body: "File exceeds 500 MB limit.")
                            return
                        }
                        if existingBody.count < requiredLength {
                            self.readRemainingBody(
                                connection: connection,
                                path: path,
                                contentType: contentType,
                                expectedLength: requiredLength,
                                accumulated: existingBody
                            )
                            return
                        }
                    }
                    self.handlePOST(path: path, contentType: contentType, body: existingBody, connection: connection)
                } else {
                    self.sendResponse(connection: connection, status: "405 Method Not Allowed", body: "Method not allowed")
                }
            } else if isComplete {
                connection.cancel()
            } else {
                self.readRequest(connection: connection, buffer: currentBuffer)
            }
        }
    }

    private func readRemainingBody(
        connection: NWConnection,
        path: String,
        contentType: String?,
        expectedLength: Int,
        accumulated: Data
    ) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 131072) { [weak self] data, _, isComplete, error in
            guard let self = self else {
                connection.cancel()
                return
            }
            if let error = error {
                NSLog("GuestHTTPServer read body error: %@", String(describing: error))
                connection.cancel()
                return
            }

            var nextData = accumulated
            if let data = data {
                nextData.append(data)
            }

            if nextData.count >= expectedLength || isComplete {
                self.handlePOST(path: path, contentType: contentType, body: nextData, connection: connection)
            } else {
                self.readRemainingBody(
                    connection: connection,
                    path: path,
                    contentType: contentType,
                    expectedLength: expectedLength,
                    accumulated: nextData
                )
            }
        }
    }

    private func handleGET(path: String, connection: NWConnection) {
        guard let url = URL(string: "http://localhost" + path) else {
            sendResponse(connection: connection, status: "400 Bad Request", body: "Invalid URL")
            return
        }

        let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let reqToken = queryItems.first(where: { $0.name == "token" })?.value

        guard reqToken == self.token else {
            sendHTMLResponse(
                connection: connection,
                status: "403 Forbidden",
                html: guestErrorHTML(title: "Access Denied", message: "This guest transfer session token is invalid or expired.")
            )
            return
        }

        if isExpired {
            sendHTMLResponse(
                connection: connection,
                status: "403 Forbidden",
                html: guestErrorHTML(title: "Session Expired", message: "This guest transfer session has expired.")
            )
            return
        }

        if url.path == "/guest" {
            sendHTMLResponse(
                connection: connection,
                status: "200 OK",
                html: guestUploadPageHTML(token: self.token)
            )
        } else {
            sendResponse(connection: connection, status: "404 Not Found", body: "Not found")
        }
    }

    private func handlePOST(path: String, contentType: String?, body: Data, connection: NWConnection) {
        guard let url = URL(string: "http://localhost" + path) else {
            sendResponse(connection: connection, status: "400 Bad Request", body: "Invalid URL")
            return
        }

        let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let reqToken = queryItems.first(where: { $0.name == "token" })?.value

        guard reqToken == self.token else {
            sendJSONResponse(connection: connection, status: "403 Forbidden", json: "{\"error\":\"Invalid or missing token\"}")
            return
        }

        if isExpired {
            sendJSONResponse(connection: connection, status: "403 Forbidden", json: "{\"error\":\"Session has expired\"}")
            return
        }

        guard url.path == "/upload" else {
            sendResponse(connection: connection, status: "404 Not Found", body: "Not found")
            return
        }

        // Parse multipart form or binary data
        var rawFilename: String = "guest_file_\(Int(Date().timeIntervalSince1970))"
        var fileData = body

        if let ct = contentType, ct.contains("multipart/form-data") {
            let boundaryParam = ct.components(separatedBy: ";").first(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("boundary=") })
            if let boundary = boundaryParam?.components(separatedBy: "=").last?.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\"")) {
                let parsed = parseMultipart(body: body, boundary: boundary)
                if let first = parsed.first {
                    rawFilename = first.filename
                    fileData = first.data
                }
            }
        }

        let safeFilename = GuestTransferSecurity.sanitizeFilename(rawFilename)
        let destination = stagingDirectory.appendingPathComponent(UUID().uuidString + "_" + safeFilename)

        do {
            try FileManager.default.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
            try fileData.write(to: destination)

            let upload = GuestPendingUpload(
                filename: safeFilename,
                fileSize: Int64(fileData.count),
                stagedURL: destination,
                status: .pending
            )
            onUploadReceived(upload)

            sendJSONResponse(
                connection: connection,
                status: "200 OK",
                json: "{\"status\":\"ok\",\"filename\":\"\(safeFilename)\",\"message\":\"File uploaded. Waiting for Mac approval.\"}"
            )
        } catch {
            NSLog("GuestHTTPServer write error: %@", String(describing: error))
            sendJSONResponse(connection: connection, status: "500 Internal Server Error", json: "{\"error\":\"Failed to stage file\"}")
        }
    }

    private struct ParsedPart {
        let filename: String
        let data: Data
    }

    private func parseMultipart(body: Data, boundary: String) -> [ParsedPart] {
        var results: [ParsedPart] = []
        let boundaryData = Data(("--" + boundary).utf8)
        let delimiter = Data("\r\n\r\n".utf8)

        var searchIndex = 0
        while searchIndex < body.count {
            guard let boundaryRange = body.range(of: boundaryData, options: [], in: searchIndex..<body.count) else {
                break
            }
            let partStart = boundaryRange.upperBound
            guard let nextBoundaryRange = body.range(of: boundaryData, options: [], in: partStart..<body.count) else {
                break
            }
            let partData = body.subdata(in: partStart..<nextBoundaryRange.lowerBound)
            if let headerEnd = partData.range(of: delimiter) {
                let headerBytes = partData.subdata(in: 0..<headerEnd.lowerBound)
                let contentData = partData.subdata(in: headerEnd.upperBound..<partData.count)
                let headerStr = String(data: headerBytes, encoding: .utf8) ?? ""

                var filename = "file.bin"
                if let fnRange = headerStr.range(of: "filename=\"") {
                    let sub = headerStr[fnRange.upperBound...]
                    if let endQuote = sub.firstIndex(of: "\"") {
                        filename = String(sub[..<endQuote])
                    }
                }

                // Strip trailing \r\n from contentData if present
                var cleanContent = contentData
                if cleanContent.count >= 2, cleanContent.suffix(2) == Data("\r\n".utf8) {
                    cleanContent = cleanContent.dropLast(2)
                }

                if !cleanContent.isEmpty {
                    results.append(ParsedPart(filename: filename, data: cleanContent))
                }
            }
            searchIndex = nextBoundaryRange.lowerBound
        }
        return results
    }

    private func sendResponse(connection: NWConnection, status: String, body: String) {
        let response = "HTTP/1.1 \(status)\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func sendHTMLResponse(connection: NWConnection, status: String, html: String) {
        let response = "HTTP/1.1 \(status)\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(html.utf8.count)\r\nConnection: close\r\n\r\n\(html)"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func sendJSONResponse(connection: NWConnection, status: String, json: String) {
        let response = "HTTP/1.1 \(status)\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: \(json.utf8.count)\r\nConnection: close\r\n\r\n\(json)"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func guestUploadPageHTML(token: String) -> String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">

          <meta name="theme-color" content="#F5F7FC" media="(prefers-color-scheme: light)">
          <meta name="theme-color" content="#151820" media="(prefers-color-scheme: dark)">
          <meta name="color-scheme" content="light dark">

          <title>SYNQ — Guest Transfer</title>

          <style>
            :root {
              color-scheme: light dark;

              /* SYNQ blue fallback */
              --accent: #3478F6;
              --accent-text: #FFFFFF;
              --accent-soft: #EAF1FF;

              /* Light appearance */
              --bg: #F5F7FC;
              --surface: #FFFFFF;
              --surface-secondary: #F0F3F9;
              --text: #202431;
              --muted: #737B8B;
              --border: #E1E6EF;
              --danger: #C65353;
              --success: #31865A;

              --radius-card: 24px;
              --radius-control: 16px;

              --shadow: 0 8px 32px rgba(35, 49, 75, .07);
            }

            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #151820;
                --surface: #202530;
                --surface-secondary: #282F3C;
                --text: #EDF0F7;
                --muted: #A0A8B8;
                --border: #343D4D;
                --danger: #FFAAA5;
                --success: #82D5A4;

                --accent: #82AAFF;
                --accent-text: #101C32;
                --accent-soft: #293A5B;

                --shadow: 0 12px 40px rgba(0, 0, 0, .20);
              }
            }

            @supports (color: AccentColor) {
              :root {
                --accent: AccentColor;
              }
            }

            @supports (color: AccentColorText) {
              :root {
                --accent-text: AccentColorText;
              }
            }

            * {
              box-sizing: border-box;
            }

            html {
              min-height: 100%;
              background: var(--bg);
            }

            body {
              margin: 0;
              min-height: 100vh;
              min-height: 100svh;

              padding:
                max(22px, env(safe-area-inset-top))
                17px
                max(22px, env(safe-area-inset-bottom));

              display: flex;
              justify-content: center;
              align-items: center;

              background:
                radial-gradient(
                  ellipse at 50% 0%,
                  color-mix(in srgb, var(--accent) 8%, transparent),
                  transparent 65%
                ),
                var(--bg);

              color: var(--text);

              font-family:
                -apple-system,
                BlinkMacSystemFont,
                "SF Pro Display",
                "SF Pro Text",
                "Segoe UI",
                Roboto,
                Helvetica,
                Arial,
                sans-serif;

              -webkit-font-smoothing: antialiased;
              text-rendering: optimizeLegibility;
            }

            button,
            input {
              font: inherit;
            }

            .container {
              width: 100%;
              max-width: 430px;

              padding: 30px 24px 23px;

              background: var(--surface);
              border: 1px solid var(--border);
              border-radius: var(--radius-card);

              box-shadow: var(--shadow);

              animation: enter .45s cubic-bezier(.2,.8,.2,1) both;
            }

            /* Header */

            .header {
              text-align: center;
              margin-bottom: 28px;
            }

            .brand {
              display: inline-block;

              margin-bottom: 5px;

              color: var(--accent);

              font-size: 16px;
              font-weight: 800;
              letter-spacing: .16em;

              cursor: default;
              user-select: none;
              -webkit-user-select: none;
              -webkit-touch-callout: none;
              -webkit-tap-highlight-color: transparent;
            }

            .signature {
              margin-bottom: 22px;

              color: var(--muted);
              opacity: .72;

              font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
              font-size: 9px;
              font-weight: 550;
              letter-spacing: .15em;
            }

            .badge {
              display: inline-block;

              margin-bottom: 13px;
              padding: 6px 12px;

              background: var(--accent-soft);
              color: var(--accent);

              border-radius: 100px;

              font-size: 12px;
              font-weight: 700;
              letter-spacing: .015em;
            }

            .title {
              margin: 0 0 8px;

              font-size: clamp(26px, 7vw, 30px);
              line-height: 1.15;
              font-weight: 750;
              letter-spacing: -.9px;
            }

            .subtitle {
              max-width: 330px;
              margin: 0 auto;

              color: var(--muted);

              font-size: 14px;
              line-height: 1.55;
            }

            /* File selection */

            .dropzone {
              display: flex;
              flex-direction: column;
              align-items: center;
              justify-content: center;

              min-height: 190px;
              padding: 28px 17px;
              margin-bottom: 19px;

              background: var(--surface-secondary);
              border: 1.5px dashed var(--border);
              border-radius: 19px;

              text-align: center;
              cursor: pointer;
              user-select: none;
              -webkit-user-select: none;
              -webkit-touch-callout: none;

              transition:
                background .2s ease,
                border-color .2s ease,
                transform .2s ease;

              -webkit-tap-highlight-color: transparent;
            }

            .dropzone:active {
              transform: scale(.985);
              background: var(--accent-soft);
              border-color: var(--accent);
            }

            .dropzone.dragover {
              background: var(--accent-soft);
              border-color: var(--accent);
            }

            .drop-icon {
              color: var(--accent);
              margin-bottom: 10px;
              display: flex;
              align-items: center;
              justify-content: center;
              transition: transform .2s ease;
            }

            .dropzone:hover .drop-icon,
            .dropzone.dragover .drop-icon {
              transform: translateY(-2px) scale(1.05);
            }

            .drop-title {
              font-size: 16px;
              font-weight: 700;
              letter-spacing: -.2px;
            }

            .drop-subtitle {
              margin-top: 7px;

              color: var(--muted);
              font-size: 13px;
            }

            .file-input {
              display: none;
            }

            .file-selected {
              display: none;
              width: 100%;

              margin-top: 16px;
              padding: 11px 13px;

              background: var(--accent-soft);
              color: var(--accent);

              border-radius: 12px;

              font-size: 13px;
              font-weight: 650;
              line-height: 1.5;

              overflow-wrap: anywhere;
            }

            .file-selected.visible {
              display: block;
              animation: appear .2s ease both;
            }

            /* Main action */

            .btn {
              width: 100%;
              min-height: 55px;
              padding: 15px 18px;

              background: var(--accent);
              color: var(--accent-text);

              border: none;
              border-radius: var(--radius-control);

              font-size: 16px;
              font-weight: 700;
              letter-spacing: -.2px;

              cursor: pointer;
              user-select: none;
              -webkit-user-select: none;
              -webkit-touch-callout: none;

              transition:
                transform .18s ease,
                opacity .18s ease,
                filter .18s ease;

              -webkit-tap-highlight-color: transparent;
            }

            .btn:not(:disabled):active {
              transform: scale(.98);
              filter: brightness(.94);
            }

            .btn:disabled {
              opacity: .40;
              cursor: not-allowed;
            }

            /* Progress */

            .progress-container {
              display: none;
              margin-top: 21px;
            }

            .progress-container.visible {
              display: block;
              animation: appear .25s ease both;
            }

            .progress-track {
              height: 7px;
              overflow: hidden;

              background: var(--surface-secondary);
              border-radius: 100px;
            }

            .progress-fill {
              width: 0%;
              height: 100%;

              background: var(--accent);
              border-radius: inherit;

              transition: width .15s linear;
            }

            .status-msg {
              min-height: 20px;
              margin-top: 13px;

              color: var(--muted);

              font-size: 13px;
              font-weight: 550;
              line-height: 1.5;
              text-align: center;
            }

            /* Engineering details */

            .system-status {
              display: flex;
              align-items: center;
              justify-content: center;
              gap: 8px;

              margin-top: 18px;

              color: var(--muted);
              opacity: .75;

              font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
              font-size: 9px;
              font-weight: 600;
              letter-spacing: .12em;
              text-transform: uppercase;
            }

            .status-dot {
              width: 6px;
              height: 6px;
              flex-shrink: 0;

              background: var(--success);
              border-radius: 50%;
            }

            .system-status.transmitting .status-dot {
              background: var(--accent);
              animation: pulse 1.3s ease-in-out infinite;
            }

            .system-status.complete .status-dot {
              background: var(--success);
              animation: none;
            }

            .system-status.error .status-dot {
              background: var(--danger);
              animation: none;
            }

            /* Footer */

            .footer {
              margin-top: 21px;
              padding-top: 15px;

              border-top: 1px solid var(--border);

              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 8px;
            }

            .footer-label,
            .footer-version {
              color: var(--muted);
              opacity: .65;

              font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
              font-size: 9px;
              letter-spacing: .08em;
            }

            /* Hidden builder detail */

            .easter-egg {
              display: none;
              margin-top: 14px;

              color: var(--accent);

              font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
              font-size: 10px;
              font-weight: 600;
              letter-spacing: .12em;
              text-align: center;
            }

            .easter-egg.visible {
              display: block;
              animation: appear .4s ease both;
            }

            /* Animations */

            @keyframes appear {
              from {
                opacity: 0;
                transform: translateY(5px);
              }

              to {
                opacity: 1;
                transform: translateY(0);
              }
            }

            @keyframes enter {
              from {
                opacity: 0;
                transform: translateY(9px) scale(.99);
              }

              to {
                opacity: 1;
                transform: translateY(0) scale(1);
              }
            }

            @keyframes pulse {
              0%, 100% {
                opacity: 1;
                box-shadow: 0 0 0 0
                  color-mix(in srgb, var(--accent) 35%, transparent);
              }

              50% {
                opacity: .55;
                box-shadow: 0 0 0 5px transparent;
              }
            }

            /* Accessibility */

            .btn:focus-visible,
            .dropzone:focus-visible,
            .brand:focus-visible {
              outline: 3px solid var(--accent);
              outline-offset: 4px;
            }

            @media (max-width: 380px) {
              body {
                padding-left: 13px;
                padding-right: 13px;
              }

              .container {
                padding: 25px 19px 21px;
              }

              .dropzone {
                min-height: 175px;
              }
            }

            @media (prefers-reduced-motion: reduce) {
              *,
              *::before,
              *::after {
                animation-duration: .01ms !important;
                transition-duration: .01ms !important;
              }
            }
          </style>
        </head>

        <body>

          <main class="container">

            <header class="header">

              <div
                class="brand"
                id="brand"
                tabindex="0"
                aria-label="SYNQ"
              >
                SYNQ
              </div>

              <div class="signature">
                PERSONAL SYSTEMS / 001
              </div>

              <span class="badge">Guest Transfer</span>

              <h1 class="title">Send to Mac</h1>

              <p class="subtitle">
                Direct local transfer over Wi-Fi.
                No account or installation required.
              </p>

            </header>

            <div
              class="dropzone"
              id="dropzone"
              role="button"
              tabindex="0"
              aria-label="Choose files to send to Mac"
            >

              <div class="drop-icon" aria-hidden="true">
                <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/>
                  <line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
              </div>

              <div class="drop-title">
                Tap to choose files
              </div>

              <div class="drop-subtitle">
                Up to 500 MB per transfer
              </div>

              <div class="file-selected" id="fileSelected"></div>

            </div>

            <input
              type="file"
              id="fileInput"
              class="file-input"
              multiple
            >

            <button class="btn" id="uploadBtn" disabled>
              Send to Mac
            </button>

            <div class="progress-container" id="progressContainer">
              <div class="progress-track">
                <div class="progress-fill" id="progressFill"></div>
              </div>
            </div>

            <div
              class="status-msg"
              id="statusMsg"
              role="status"
              aria-live="polite"
            ></div>

            <div class="system-status" id="systemStatus">
              <span class="status-dot"></span>
              <span id="systemStatusText">LOCAL LINK READY</span>
            </div>

            <div class="easter-egg" id="easterEgg">
              HELLO, BUILDER. KEEP MAKING THINGS.
            </div>

            <footer class="footer">
              <span class="footer-label">ENGINEERED FOR THE EVERYDAY</span>
              <span class="footer-version">SYNQ / 4.5.4</span>
            </footer>

          </main>

          <script>
            let selectedFiles = [];
            let isUploading = false;

            // Active session token injected by the Mac HTTP server.
            const token = "\(token)";

            const fileInput = document.getElementById("fileInput");
            const dropzone = document.getElementById("dropzone");
            const fileSelected = document.getElementById("fileSelected");
            const uploadBtn = document.getElementById("uploadBtn");
            const statusMsg = document.getElementById("statusMsg");
            const progressContainer = document.getElementById("progressContainer");
            const progressFill = document.getElementById("progressFill");
            const systemStatus = document.getElementById("systemStatus");
            const systemStatusText = document.getElementById("systemStatusText");
            const easterEgg = document.getElementById("easterEgg");
            const brand = document.getElementById("brand");

            // System status.
            function setSystemStatus(state, label) {
              systemStatus.className = "system-status";

              if (state) {
                systemStatus.classList.add(state);
              }

              systemStatusText.textContent = label;
            }

            // File picker.
            function openFilePicker() {
              if (isUploading) return;
              fileInput.click();
            }

            dropzone.addEventListener("click", openFilePicker);

            dropzone.addEventListener("keydown", (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                openFilePicker();
              }
            });

            fileInput.addEventListener("change", () => {
              onFilePicked(fileInput.files);
            });

            function onFilePicked(files) {
              if (!files || files.length === 0) return;

              selectedFiles = Array.from(files);

              const label = selectedFiles.length === 1
                ? selectedFiles[0].name
                : selectedFiles.length + " files selected";

              fileSelected.textContent = label;
              fileSelected.classList.add("visible");

              uploadBtn.disabled = false;
              uploadBtn.textContent = "Send to Mac";

              statusMsg.textContent = "";
              statusMsg.style.color = "var(--muted)";

              progressContainer.classList.remove("visible");
              progressFill.style.width = "0%";

              setSystemStatus("", "LOCAL LINK READY");
            }

            // Desktop drag and drop.
            ["dragenter", "dragover"].forEach((eventName) => {
              dropzone.addEventListener(eventName, (event) => {
                event.preventDefault();
                dropzone.classList.add("dragover");
              });
            });

            ["dragleave", "drop"].forEach((eventName) => {
              dropzone.addEventListener(eventName, (event) => {
                event.preventDefault();
                dropzone.classList.remove("dragover");
              });
            });

            dropzone.addEventListener("drop", (event) => {
              if (isUploading) return;

              const files = event.dataTransfer?.files;

              if (files && files.length) {
                onFilePicked(files);
              }
            });

            // Hidden builder detail: five taps on SYNQ.
            let brandTapCount = 0;
            let brandTapTimer;

            function handleBrandTap() {
              brandTapCount++;

              clearTimeout(brandTapTimer);

              if (brandTapCount >= 5) {
                brandTapCount = 0;

                easterEgg.classList.add("visible");

                setTimeout(() => {
                  easterEgg.classList.remove("visible");
                }, 4500);

              } else {
                brandTapTimer = setTimeout(() => {
                  brandTapCount = 0;
                }, 1500);
              }
            }

            brand.addEventListener("click", handleBrandTap);

            brand.addEventListener("keydown", (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                handleBrandTap();
              }
            });

            // Upload.
            uploadBtn.addEventListener("click", startUpload);

            async function startUpload() {
              if (selectedFiles.length === 0 || isUploading) return;

              isUploading = true;

              uploadBtn.disabled = true;
              uploadBtn.textContent = "Sending…";

              progressContainer.classList.add("visible");
              progressFill.style.width = "0%";

              statusMsg.textContent = "Sending to Mac…";
              statusMsg.style.color = "var(--muted)";

              setSystemStatus("transmitting", "TRANSMITTING");

              for (let i = 0; i < selectedFiles.length; i++) {
                const file = selectedFiles[i];
                const formData = new FormData();

                formData.append("file", file, file.name);

                try {
                  await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();

                    xhr.open(
                      "POST",
                      "/upload?token=" + encodeURIComponent(token)
                    );

                    xhr.upload.onprogress = (event) => {
                      if (event.lengthComputable) {
                        const totalDone =
                          (
                            (i + event.loaded / event.total) /
                            selectedFiles.length
                          ) * 100;

                        progressFill.style.width =
                          Math.round(totalDone) + "%";
                      }
                    };

                    xhr.onload = () => {
                      if (xhr.status >= 200 && xhr.status < 300) {
                        resolve();
                      } else {
                        reject(new Error("Server error " + xhr.status));
                      }
                    };

                    xhr.onerror = () => {
                      reject(new Error("Network connection lost"));
                    };

                    xhr.onabort = () => {
                      reject(new Error("Transfer cancelled"));
                    };

                    xhr.send(formData);
                  });

                } catch (error) {
                  statusMsg.textContent = "Error: " + error.message;
                  statusMsg.style.color = "var(--danger)";

                  uploadBtn.disabled = false;
                  uploadBtn.textContent = "Try Again";

                  isUploading = false;

                  setSystemStatus("error", "TRANSFER INTERRUPTED");
                  return;
                }
              }

              progressFill.style.width = "100%";

              statusMsg.textContent =
                "Transferred! Waiting for Mac acceptance.";

              statusMsg.style.color = "var(--success)";

              setSystemStatus("complete", "TRANSFER COMPLETE");

              selectedFiles = [];
              fileInput.value = "";

              fileSelected.textContent = "";
              fileSelected.classList.remove("visible");

              setTimeout(() => {
                uploadBtn.disabled = false;
                uploadBtn.textContent = "Send More Files";
                isUploading = false;
              }, 1200);
            }

          </script>

        </body>
        </html>
        """
    }

    private func guestErrorHTML(title: String, message: String) -> String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
          <meta name="theme-color" content="#F5F7FC" media="(prefers-color-scheme: light)">
          <meta name="theme-color" content="#151820" media="(prefers-color-scheme: dark)">
          <meta name="color-scheme" content="light dark">
          <title>SYNQ — \(title)</title>
          <style>
            :root {
              color-scheme: light dark;
              --bg: #F5F7FC;
              --surface: #FFFFFF;
              --text: #202431;
              --muted: #737B8B;
              --border: #E1E6EF;
              --danger: #C65353;
              --accent: #3478F6;
              --radius-card: 24px;
              --shadow: 0 8px 32px rgba(35, 49, 75, .07);
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #151820;
                --surface: #202530;
                --text: #EDF0F7;
                --muted: #A0A8B8;
                --border: #343D4D;
                --danger: #FFAAA5;
                --accent: #82AAFF;
                --shadow: 0 12px 40px rgba(0, 0, 0, .20);
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; min-height: 100vh; min-height: 100svh;
              padding: 24px 16px;
              display: flex; justify-content: center; align-items: center;
              background: var(--bg); color: var(--text);
              font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, sans-serif;
              -webkit-font-smoothing: antialiased;
            }
            .card {
              width: 100%; max-width: 420px; text-align: center;
              background: var(--surface); border: 1px solid var(--border);
              border-radius: var(--radius-card); padding: 36px 24px 30px;
              box-shadow: var(--shadow);
            }
            .icon {
              width: 48px; height: 48px; margin: 0 auto 16px;
              border-radius: 50%; display: flex; align-items: center; justify-content: center;
              background: color-mix(in srgb, var(--danger) 12%, transparent);
              color: var(--danger);
            }
            h2 { margin: 0 0 8px 0; font-size: 22px; font-weight: 700; color: var(--text); }
            p { color: var(--muted); font-size: 14px; line-height: 1.5; margin: 0; }
          </style>
        </head>
        <body>
          <div class="card">
            <div class="icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="15" y1="9" x2="9" y2="15"></line>
                <line x1="9" y1="9" x2="15" y2="15"></line>
              </svg>
            </div>
            <h2>\(title)</h2>
            <p>\(message)</p>
          </div>
        </body>
        </html>
        """
    }
}

/// Coordinates the Guest Transfer lifecycle, UI state, and file acceptance.
@MainActor
public final class GuestTransferManager: ObservableObject {
    @Published public private(set) var isSessionActive: Bool = false
    @Published public private(set) var sessionToken: String?
    @Published public private(set) var sessionURLString: String?
    @Published public private(set) var qrCodeImage: NSImage?
    @Published public private(set) var timeRemaining: TimeInterval = 0
    @Published public private(set) var pendingUploads: [GuestPendingUpload] = []
    @Published public private(set) var localIP: String = "127.0.0.1"
    @Published public var errorMessage: String?

    private var server: GuestHTTPServer?
    private var bonjourService: NetService?
    private var timer: Timer?
    private var currentPort: UInt16 = 42460
    private let stagingDirectory: URL

    public init() {
        let tempBase = FileManager.default.temporaryDirectory
        self.stagingDirectory = tempBase.appendingPathComponent("synq_guest_staging", isDirectory: true)
    }

    /// Starts a guest session for the specified duration (default: 600s / 10 minutes).
    public func startSession(duration: TimeInterval = 600, port: UInt16 = 42460) {
        cancelSession()

        self.currentPort = port
        self.localIP = GuestTransferSecurity.getLocalIPAddress()
        guard !localIP.hasPrefix("127.") else {
            self.errorMessage = "Connect your Mac to Wi‑Fi or Ethernet before starting Guest Share."
            return
        }
        let token = GuestTransferSecurity.generateSecureToken()

        let sessionStagingDir = stagingDirectory.appendingPathComponent(token, isDirectory: true)
        try? FileManager.default.createDirectory(at: sessionStagingDir, withIntermediateDirectories: true)

        let expirationDate = Date().addingTimeInterval(duration)
        let serverInstance = GuestHTTPServer(
            port: port,
            token: token,
            expiresAt: expirationDate,
            stagingDirectory: sessionStagingDir,
            onFailure: { [weak self] message in
                DispatchQueue.main.async {
                    guard let self, self.sessionToken == token else { return }
                    self.cancelSession()
                    self.errorMessage = "Guest sharing couldn’t start: \(message)"
                }
            },
            onUploadReceived: { [weak self] upload in
                DispatchQueue.main.async {
                    self?.pendingUploads.insert(upload, at: 0)
                }
            }
        )

        do {
            try serverInstance.start()
            self.server = serverInstance
            self.sessionToken = token
            self.timeRemaining = duration
            self.isSessionActive = true
            self.pendingUploads = []
            self.errorMessage = nil

            // Advertise local service via Bonjour / mDNS while guest receiving is active
            let service = NetService(domain: "local.", type: "_http._tcp.", name: "SYNQ Guest", port: Int32(port))
            service.publish()
            self.bonjourService = service

            updateSessionURL()

            self.timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self = self else { return }
                    if self.timeRemaining > 1 {
                        self.timeRemaining -= 1
                    } else {
                        self.timeRemaining = 0
                        self.cancelSession()
                    }
                }
            }
        } catch {
            self.errorMessage = "Could not start local guest server: \(error.localizedDescription)"
        }
    }

    private func updateSessionURL() {
        guard let token = sessionToken else { return }
        let urlString = "http://\(localIP):\(currentPort)/guest?token=\(token)"
        self.sessionURLString = urlString
        self.qrCodeImage = GuestTransferSecurity.generateQRCode(from: urlString)
    }

    /// Cancels active session, stops Bonjour advertisement, and securely wipes unaccepted staged files.
    public func cancelSession() {
        timer?.invalidate()
        timer = nil

        bonjourService?.stop()
        bonjourService = nil

        server?.stop()
        server = nil

        // Clean up staged uploads that were not accepted
        if let token = sessionToken {
            let sessionStagingDir = stagingDirectory.appendingPathComponent(token, isDirectory: true)
            try? FileManager.default.removeItem(at: sessionStagingDir)
        }

        isSessionActive = false
        sessionToken = nil
        sessionURLString = nil
        qrCodeImage = nil
        timeRemaining = 0
    }

    /// Accepts an upload, moving it from staging to the user's Downloads directory.
    public func acceptUpload(
        _ upload: GuestPendingUpload,
        destinationDirectory: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
    ) {
        guard let index = pendingUploads.firstIndex(where: { $0.id == upload.id }) else { return }
        guard pendingUploads[index].status == .pending else { return }

        let destURL = GuestTransferSecurity.uniqueDestinationURL(for: upload.filename, in: destinationDirectory)
        do {
            try FileManager.default.moveItem(at: upload.stagedURL, to: destURL)
            pendingUploads[index].status = .accepted
        } catch {
            NSLog("Failed to move accepted guest file: %@", String(describing: error))
            errorMessage = "Failed to save file: \(error.localizedDescription)"
        }
    }

    /// Rejects an upload and deletes it from disk immediately.
    public func rejectUpload(_ upload: GuestPendingUpload) {
        guard let index = pendingUploads.firstIndex(where: { $0.id == upload.id }) else { return }
        try? FileManager.default.removeItem(at: upload.stagedURL)
        pendingUploads[index].status = .rejected
    }

    public func acceptAll(destinationDirectory: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!) {
        for upload in pendingUploads where upload.status == .pending {
            acceptUpload(upload, destinationDirectory: destinationDirectory)
        }
    }

    public func rejectAll() {
        for upload in pendingUploads where upload.status == .pending {
            rejectUpload(upload)
        }
    }
}
