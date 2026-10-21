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
    private let queue = DispatchQueue(label: "dev.synq.guest.server", qos: .userInitiated)

    private var isExpired: Bool {
        Date() >= expiresAt
    }

    init(
        port: UInt16 = 42460,
        token: String,
        expiresAt: Date,
        stagingDirectory: URL,
        onUploadReceived: @escaping @Sendable (GuestPendingUpload) -> Void
    ) {
        self.port = port
        self.token = token
        self.expiresAt = expiresAt
        self.stagingDirectory = stagingDirectory
        self.onUploadReceived = onUploadReceived
    }

    func start() throws {
        let params = NWParameters.tcp
        let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port) ?? 42460)
        listener.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }
        listener.stateUpdateHandler = { state in
            if case let .failed(error) = state {
                NSLog("GuestHTTPServer listener error: %@", String(describing: error))
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
          <title>SYNQ — Guest Transfer</title>
          <style>
            :root {
              --bg: #FAF8F5;
              --card: #FFFFFF;
              --text: #1B1E1B;
              --muted: #5D635C;
              --primary: #385E43;
              --primary-hover: #2B4A34;
              --border: #E0E5DE;
              --accent: #A84E38;
              --radius: 18px;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #141715;
                --card: #1D221F;
                --text: #E6EAE4;
                --muted: #959D93;
                --primary: #9BC5A7;
                --primary-hover: #BCE2C6;
                --border: #2C352E;
                --accent: #FFB4A2;
              }
            }
            body {
              margin: 0;
              padding: 24px 16px;
              background-color: var(--bg);
              color: var(--text);
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
              display: flex;
              justify-content: center;
              align-items: center;
              min-height: 90vh;
              box-sizing: border-box;
            }
            .container {
              width: 100%;
              max-width: 440px;
              background: var(--card);
              border: 1px solid var(--border);
              border-radius: var(--radius);
              padding: 28px 24px;
              box-shadow: 0 4px 24px rgba(0,0,0,0.06);
              box-sizing: border-box;
            }
            .header {
              text-align: center;
              margin-bottom: 24px;
            }
            .title {
              font-size: 22px;
              font-weight: 700;
              margin: 0 0 6px 0;
              letter-spacing: -0.3px;
            }
            .subtitle {
              font-size: 14px;
              color: var(--muted);
              margin: 0;
              line-height: 1.4;
            }
            .badge {
              display: inline-block;
              background: rgba(56, 94, 67, 0.12);
              color: var(--primary);
              font-size: 12px;
              font-weight: 600;
              padding: 4px 10px;
              border-radius: 20px;
              margin-bottom: 12px;
            }
            .dropzone {
              border: 2px dashed var(--border);
              border-radius: 14px;
              padding: 28px 16px;
              text-align: center;
              margin-bottom: 20px;
              cursor: pointer;
              transition: all 0.2s ease;
            }
            .dropzone:active {
              background: rgba(56, 94, 67, 0.05);
              border-color: var(--primary);
            }
            .file-input {
              display: none;
            }
            .btn {
              width: 100%;
              padding: 14px;
              background: var(--primary);
              color: #FFFFFF;
              border: none;
              border-radius: 12px;
              font-size: 16px;
              font-weight: 600;
              cursor: pointer;
              transition: background 0.2s;
              box-sizing: border-box;
            }
            .btn:disabled {
              opacity: 0.5;
              cursor: not-allowed;
            }
            .progress-container {
              display: none;
              margin-top: 18px;
            }
            .progress-bar {
              height: 8px;
              background: var(--border);
              border-radius: 4px;
              overflow: hidden;
            }
            .progress-fill {
              height: 100%;
              width: 0%;
              background: var(--primary);
              transition: width 0.1s linear;
            }
            .status-msg {
              font-size: 14px;
              margin-top: 14px;
              text-align: center;
              font-weight: 500;
            }
            .file-selected {
              font-size: 14px;
              font-weight: 600;
              margin-top: 8px;
              color: var(--primary);
              word-break: break-all;
            }
          </style>
        </head>
        <body>
          <div class="container">
            <div class="header">
              <span class="badge">SYNQ Guest Transfer</span>
              <h1 class="title">Send to Mac</h1>
              <p class="subtitle">Direct local transfer over Wi-Fi. No account or installation required.</p>
            </div>

            <div class="dropzone" id="dropzone" onclick="document.getElementById('fileInput').click()">
              <div style="font-size: 32px; margin-bottom: 8px;">📁</div>
              <div style="font-size: 15px; font-weight: 500;">Tap to choose files</div>
              <div style="font-size: 12px; color: var(--muted); margin-top: 4px;">Up to 500 MB per transfer</div>
              <div class="file-selected" id="fileSelected"></div>
            </div>

            <input type="file" id="fileInput" class="file-input" multiple onchange="onFilePicked(this.files)">

            <button class="btn" id="uploadBtn" disabled onclick="startUpload()">Send to Mac</button>

            <div class="progress-container" id="progressContainer">
              <div class="progress-bar">
                <div class="progress-fill" id="progressFill"></div>
              </div>
            </div>

            <div class="status-msg" id="statusMsg"></div>
          </div>

          <script>
            let selectedFiles = [];
            const token = "\(token)";

            function onFilePicked(files) {
              if (!files || files.length === 0) return;
              selectedFiles = Array.from(files);
              const label = selectedFiles.length === 1 ? selectedFiles[0].name : selectedFiles.length + " files selected";
              document.getElementById('fileSelected').innerText = label;
              document.getElementById('uploadBtn').disabled = false;
              document.getElementById('statusMsg').innerText = "";
            }

            async function startUpload() {
              if (selectedFiles.length === 0) return;
              const btn = document.getElementById('uploadBtn');
              const progressContainer = document.getElementById('progressContainer');
              const progressFill = document.getElementById('progressFill');
              const statusMsg = document.getElementById('statusMsg');

              btn.disabled = true;
              progressContainer.style.display = 'block';
              progressFill.style.width = '0%';
              statusMsg.innerText = "Sending to Mac…";
              statusMsg.style.color = "var(--text)";

              for (let i = 0; i < selectedFiles.length; i++) {
                const file = selectedFiles[i];
                const formData = new FormData();
                formData.append("file", file, file.name);

                try {
                  await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.open("POST", "/upload?token=" + encodeURIComponent(token));
                    xhr.upload.onprogress = (e) => {
                      if (e.lengthComputable) {
                        const totalDone = ((i + (e.loaded / e.total)) / selectedFiles.length) * 100;
                        progressFill.style.width = Math.round(totalDone) + "%";
                      }
                    };
                    xhr.onload = () => {
                      if (xhr.status >= 200 && xhr.status < 300) {
                        resolve();
                      } else {
                        reject(new Error("Server error " + xhr.status));
                      }
                    };
                    xhr.onerror = () => reject(new Error("Network connection lost"));
                    xhr.send(formData);
                  });
                } catch (err) {
                  statusMsg.innerText = "Error: " + err.message;
                  statusMsg.style.color = "var(--accent)";
                  btn.disabled = false;
                  return;
                }
              }

              progressFill.style.width = "100%";
              statusMsg.innerText = "Transferred! Waiting for Mac acceptance.";
              statusMsg.style.color = "var(--primary)";
              document.getElementById('fileSelected').innerText = "";
              selectedFiles = [];
              setTimeout(() => {
                btn.disabled = false;
                btn.innerText = "Send More Files";
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
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>SYNQ — \(title)</title>
          <style>
            body {
              margin: 0; padding: 40px 20px;
              background: #FAF8F5; color: #1B1E1B;
              font-family: -apple-system, sans-serif;
              display: flex; justify-content: center; align-items: center; min-height: 80vh;
            }
            .card {
              max-width: 380px; width: 100%; text-align: center;
              background: #FFFFFF; border: 1px solid #E0E5DE; border-radius: 18px; padding: 32px 24px;
            }
            h2 { margin: 0 0 10px 0; color: #A84E38; }
            p { color: #5D635C; font-size: 14px; line-height: 1.5; margin: 0; }
          </style>
        </head>
        <body>
          <div class="card">
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
    public enum HostPreference: String, CaseIterable, Identifiable, Sendable {
        case friendly = "synq.local"
        case ip = "IP Address"

        public var id: String { rawValue }
    }

    @Published public private(set) var isSessionActive: Bool = false
    @Published public private(set) var sessionToken: String?
    @Published public private(set) var sessionURLString: String?
    @Published public private(set) var qrCodeImage: NSImage?
    @Published public private(set) var timeRemaining: TimeInterval = 0
    @Published public private(set) var pendingUploads: [GuestPendingUpload] = []
    @Published public private(set) var localIP: String = "127.0.0.1"
    @Published public var hostPreference: HostPreference = .friendly {
        didSet {
            updateSessionURL()
        }
    }
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
        let token = GuestTransferSecurity.generateSecureToken()

        let sessionStagingDir = stagingDirectory.appendingPathComponent(token, isDirectory: true)
        try? FileManager.default.createDirectory(at: sessionStagingDir, withIntermediateDirectories: true)

        let expirationDate = Date().addingTimeInterval(duration)
        let serverInstance = GuestHTTPServer(
            port: port,
            token: token,
            expiresAt: expirationDate,
            stagingDirectory: sessionStagingDir,
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
        let host = (hostPreference == .friendly) ? "synq.local" : localIP
        let urlString = "http://\(host):\(currentPort)/guest?token=\(token)"
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
