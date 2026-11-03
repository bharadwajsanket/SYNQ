import SwiftUI

struct AnimatedWelcomeView: View {
    let onGetStarted: () -> Void
    var onPairWithQR: (() -> Void)? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hasAppeared = false
    @State private var pulsePhase: CGFloat = 0.0

    init(onGetStarted: @escaping () -> Void, onPairWithQR: (() -> Void)? = nil) {
        self.onGetStarted = onGetStarted
        self.onPairWithQR = onPairWithQR
    }

    var body: some View {
        VStack(spacing: 14) {
            // Header: App icon + title
            VStack(spacing: 6) {
                Image(nsImage: makeSynqDeviceIcon(size: 46))
                    .frame(width: 46, height: 46)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .shadow(color: Color.black.opacity(0.12), radius: 5, x: 0, y: 2)
                    .scaleEffect(hasAppeared || reduceMotion ? 1.0 : 0.9)
                    .opacity(hasAppeared || reduceMotion ? 1.0 : 0.0)

                Text(L10n.text("welcome.title", fallback: "Welcome to SYNQ"))
                    .font(.title2.weight(.bold))

                Text(L10n.text("welcome.subtitle", fallback: "Fast, private sharing across your Mac & Android."))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            // Device Connection Animation
            deviceConnectionIllustration
                .frame(height: 58)
                .frame(maxWidth: 340)
                .padding(.horizontal, 16)

            // Value pillars
            VStack(alignment: .leading, spacing: 8) {
                welcomeFeatureRow(
                    icon: "lock.shield",
                    title: "Local & Private",
                    detail: "End-to-end encrypted direct connection. No cloud accounts, relays, or tracking."
                )
                welcomeFeatureRow(
                    icon: "bolt.fill",
                    title: "High Performance",
                    detail: "Stream large files, share clipboards, and sync notifications at local Wi-Fi speeds."
                )
            }
            .frame(maxWidth: 380)

            // Primary action
            VStack(spacing: 8) {
                Button(action: onGetStarted) {
                    HStack {
                        Text(L10n.text("welcome.continue", fallback: "Get Started"))
                            .fontWeight(.semibold)
                        Image(systemName: "arrow.right")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 2)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: 280)
                .accessibilityLabel(L10n.text("welcome.continue", fallback: "Get Started"))
                .accessibilityHint("Closes the welcome guide and starts device discovery")

                if let onPairWithQR {
                    Button(action: onPairWithQR) {
                        HStack(spacing: 6) {
                            Image(systemName: "qrcode")
                                .font(.system(size: 12, weight: .medium))
                            Text("Pair with QR Code")
                                .font(.callout.weight(.medium))
                        }
                        .foregroundStyle(Color.accentColor)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Pair with QR Code")
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 18)
        .onAppear {
            if reduceMotion {
                hasAppeared = true
                pulsePhase = 0.5
            } else {
                withAnimation(.easeOut(duration: 0.45)) {
                    hasAppeared = true
                }
                withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) {
                    pulsePhase = 1.0
                }
            }
        }
    }

    private var deviceConnectionIllustration: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let leftX: CGFloat = 32
            let rightX: CGFloat = width - 32
            let centerY = proxy.size.height / 2

            ZStack {
                // Background Track Line
                Path { path in
                    path.move(to: CGPoint(x: leftX + 16, y: centerY))
                    path.addLine(to: CGPoint(x: rightX - 16, y: centerY))
                }
                .stroke(
                    Color.primary.opacity(0.12),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [4, 4])
                )

                // Traveling pulse beam
                if hasAppeared {
                    let pulseX = (leftX + 20) + (rightX - leftX - 40) * pulsePhase
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 8, height: 8)
                        .shadow(color: Color.accentColor.opacity(0.6), radius: 4)
                        .position(x: pulseX, y: centerY)
                }

                // Mac Device Icon (Left)
                HStack {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(Color.secondary.opacity(0.12))
                            .frame(width: 44, height: 44)

                        Image(systemName: "laptopcomputer")
                            .font(.system(size: 20))
                            .foregroundStyle(Color.primary)
                    }
                    .offset(x: hasAppeared || reduceMotion ? 0 : -10)
                    .opacity(hasAppeared || reduceMotion ? 1.0 : 0.0)

                    Spacer()

                    // Phone Device Icon (Right)
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(Color.secondary.opacity(0.12))
                            .frame(width: 44, height: 44)

                        Image(systemName: "iphone")
                            .font(.system(size: 20))
                            .foregroundStyle(Color.primary)
                    }
                    .offset(x: hasAppeared || reduceMotion ? 0 : 10)
                    .opacity(hasAppeared || reduceMotion ? 1.0 : 0.0)
                }
            }
        }
    }

    private func welcomeFeatureRow(icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 18, height: 18)
                .padding(.top, 1)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(detail)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

