import SwiftUI

// MARK: - Call Picker Panel

enum CallPickerTab: String, CaseIterable {
    case favourites = "Favourites"
    case allContacts = "All Contacts"
}

/// In-panel calling screen showing favourites, synced contacts, and quick-dial controls.
struct CallContactPickerView: View {
    @ObservedObject var favourites: FavouriteContactsStore
    let onDial: (String) -> Void
    let onManualDial: () -> Void
    var onSyncContacts: (() -> Void)? = nil
    var onSendSMS: ((String, String, @escaping (Result<Void, SmsError>) -> Void) -> Void)? = nil
    let onClose: () -> Void

    @State private var selectedTab: CallPickerTab = .favourites
    @State private var manualNumber: String = ""
    @State private var searchQuery: String = ""
    @State private var showAddSheet = false
    @State private var pendingCall: PendingContactCall?
    @State private var smsTarget: SMSTarget?

    private struct PendingContactCall: Identifiable {
        let id = UUID()
        let name: String
        let number: String
    }

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    Button(action: onClose) {
                        Label("Back", systemImage: "chevron.left")
                            .font(.system(size: 12, weight: .medium))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(Color.primary.opacity(0.08), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityLabel("Back to SYNQ")

                    Spacer()
                    VStack(spacing: 2) {
                        Text("Call from Mac").font(.headline)
                        Text("Calls are placed from your phone")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "phone.arrow.up.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 30, height: 30)
                        .background(Color.accentColor.opacity(0.10), in: Circle())
                }

                // Tab selector (Favourites / All Contacts)
                Picker("", selection: $selectedTab) {
                    Text("Favourites (\(favourites.allFavourites.count))")
                        .tag(CallPickerTab.favourites)
                    Text("All Contacts (\(favourites.syncedContacts.count))")
                        .tag(CallPickerTab.allContacts)
                }
                .pickerStyle(.segmented)
                .tint(Color.accentColor)

                Divider()

                VStack(alignment: .leading, spacing: 10) {
                    switch selectedTab {
                    case .favourites:
                        favouritesSection
                    case .allContacts:
                        allContactsSection
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

                Divider()

                manualDialBar
            }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            // In-panel call confirmation overlay: dismissing only cancels the dialog and keeps the SYNQ popover open.
            if let target = pendingCall {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.easeInOut(duration: 0.15)) {
                            pendingCall = nil
                        }
                    }

                VStack(spacing: 12) {
                    Image(systemName: "phone.arrow.up.right")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 40, height: 40)
                        .background(Color.accentColor.opacity(0.12), in: Circle())

                    Text("Call \(target.name)?")
                        .font(.headline)
                        .multilineTextAlignment(.center)

                    Text("Place a call to \(target.number) using your phone?")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)

                    HStack(spacing: 10) {
                        Button("Cancel") {
                            withAnimation(.easeInOut(duration: 0.15)) {
                                pendingCall = nil
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.regular)
                        .keyboardShortcut(.cancelAction)

                        Button {
                            let num = target.number
                            withAnimation(.easeInOut(duration: 0.15)) {
                                pendingCall = nil
                            }
                            onDial(num)
                        } label: {
                            Label("Call", systemImage: "phone.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                        .keyboardShortcut(.defaultAction)
                    }
                    .padding(.top, 4)
                }
                .padding(18)
                .frame(maxWidth: 260)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(.ultraThickMaterial)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                        )
                        .shadow(color: Color.black.opacity(0.25), radius: 12, y: 5)
                )
                .transition(.scale(scale: 0.95).combined(with: .opacity))
            }

            // In-panel SMS composer overlay: keeps the popover open on Cancel, backdrop tap, or Send.
            if let target = smsTarget, let onSendSMS = onSendSMS {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.easeInOut(duration: 0.15)) {
                            smsTarget = nil
                        }
                    }

                SMSComposerSheet(target: target, onSend: onSendSMS, onDismiss: {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        smsTarget = nil
                    }
                })
                .frame(maxWidth: 308)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(.ultraThickMaterial)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                        )
                        .shadow(color: Color.black.opacity(0.25), radius: 12, y: 5)
                )
                .transition(.scale(scale: 0.95).combined(with: .opacity))
            }
        }
        .onAppear {
            if favourites.allFavourites.isEmpty && !favourites.syncedContacts.isEmpty {
                selectedTab = .allContacts
            }
        }
        .onChange(of: favourites.syncedContacts.count) { count in
            if favourites.allFavourites.isEmpty && count > 0 {
                selectedTab = .allContacts
            }
        }
    }

    private var manualDialBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "phone")
                .foregroundStyle(.secondary)
            TextField("Phone number", text: $manualNumber)
                .textFieldStyle(.plain)
                .onSubmit { dialManual() }
            Button(action: onManualDial) {
                Image(systemName: "doc.on.clipboard")
            }
            .buttonStyle(.borderless)
            .help("Paste number from clipboard")
            if !manualNumber.isEmpty {
                Button(action: dialManual) {
                    Label("Call", systemImage: "phone.fill")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .keyboardShortcut(.return, modifiers: [])
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color.primary.opacity(0.055), in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Favourites View
    @ViewBuilder
    private var favouritesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if favourites.isSyncing {
                VStack(spacing: 10) {
                    ProgressView()
                        .controlSize(.regular)
                    Text("Syncing contacts from phone…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 30)
            } else if favourites.allFavourites.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "phone.arrow.up.right")
                        .font(.system(size: 26))
                        .foregroundStyle(Color.accentColor.opacity(0.8))
                    Text("Your favourite contacts appear here")
                        .font(.caption.weight(.medium))
                    if let status = favourites.syncStatus, !status.contains("available") {
                        Text(status)
                            .font(.caption2)
                            .foregroundStyle(.orange)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 16)
                    } else {
                        Text("Add one from your synced phone contacts.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    if !favourites.syncedContacts.isEmpty {
                        Button {
                            selectedTab = .allContacts
                        } label: {
                            Label("Browse \(favourites.syncedContacts.count) contacts", systemImage: "person.crop.rectangle.stack")
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(Color.accentColor.opacity(0.13), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                    } else {
                        Button {
                            favourites.startSync()
                            onSyncContacts?()
                        } label: {
                            Label("Sync from Phone", systemImage: "arrow.clockwise")
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(Color.accentColor.opacity(0.13), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .top)
                .padding(.top, 20)
            } else {
                HStack {
                    Label("Favourites", systemImage: "star.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("Tap to call")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                        ForEach(favourites.allFavourites) { contact in
                            FavouriteContactTile(contact: contact, onTap: {
                                requestCall(name: contact.name, number: contact.number)
                            }, onSMS: {
                                smsTarget = SMSTarget(name: contact.name, number: contact.number)
                            })
                        }
                }
            }

            Spacer(minLength: 0)

            Button {
                showAddSheet = true
            } label: {
                Label("Add favourite contact", systemImage: "star.badge.plus")
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $showAddSheet) {
                AddFavouriteSheet(store: favourites)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    // MARK: - All Contacts View
    @ViewBuilder
    private var allContactsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Search field
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                TextField("Search contacts…", text: $searchQuery)
                    .font(.caption)
                    .textFieldStyle(.plain)
                if !searchQuery.isEmpty {
                    Button {
                        searchQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.tertiary)
                            .font(.caption)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(6)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 6))

            let filtered = favourites.filteredContacts(query: searchQuery)
            if favourites.isSyncing && favourites.syncedContacts.isEmpty {
                VStack(spacing: 10) {
                    ProgressView()
                        .controlSize(.regular)
                    Text("Syncing contacts from phone…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
            } else if filtered.isEmpty {
                VStack(spacing: 8) {
                    if favourites.syncedContacts.isEmpty {
                        Text("No contacts synced from phone yet.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let status = favourites.syncStatus, !status.contains("available") {
                            Text(status)
                                .font(.caption2)
                                .foregroundStyle(.orange)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 16)
                        }
                        Button {
                            favourites.startSync()
                            onSyncContacts?()
                        } label: {
                            Label("Sync from Phone", systemImage: "arrow.clockwise")
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.accentColor.opacity(0.13), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                    } else {
                        Text("No contacts matching \"\(searchQuery)\"")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            } else {
                ScrollView(.vertical, showsIndicators: true) {
                    LazyVStack(spacing: 6) {
                        ForEach(filtered) { contact in
                            SyncedContactRowView(
                                contact: contact,
                                isFavourite: { num in favourites.isFavourite(number: num) },
                                onDialNumber: { num in requestCall(name: contact.name, number: num) },
                                onSMSNumber: { num, name in smsTarget = SMSTarget(name: name, number: num) },
                                onToggleFavourite: { name, num in favourites.toggleFavourite(name: name, number: num) }
                            )
                        }
                    }
                }
                .frame(maxHeight: .infinity)
            }

            // Sync from Phone action row
            HStack {
                if let status = favourites.syncStatus {
                    Text(status)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button {
                    favourites.startSync()
                    onSyncContacts?()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: favourites.isSyncing ? "arrow.triangle.2.circlepath" : "arrow.clockwise")
                            .rotationEffect(.degrees(favourites.isSyncing ? 360 : 0))
                            .animation(favourites.isSyncing ? Animation.linear(duration: 1).repeatForever(autoreverses: false) : .default, value: favourites.isSyncing)
                        Text(favourites.isSyncing ? "Syncing…" : "Sync from Phone")
                    }
                    .font(.caption2)
                    .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .disabled(favourites.isSyncing)
            }
        }
    }

    private func dialManual() {
        let trimmed = manualNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onDial(trimmed)
        manualNumber = ""
    }

    private func requestCall(name: String, number: String) {
        pendingCall = PendingContactCall(name: name, number: number)
    }
}

// MARK: - Synced Contact Row View (Supports Single & Multiple Numbers)

struct SyncedContactRowView: View {
    let contact: SyncedContact
    let isFavourite: (String) -> Bool
    let onDialNumber: (String) -> Void
    var onSMSNumber: ((String, String) -> Void)? = nil
    let onToggleFavourite: (String, String) -> Void

    @State private var isExpanded = false
    @State private var isHovered = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                // Tapping contact info dials (if 1 number) or expands (if multiple)
                Button {
                    if contact.numbers.count == 1, let single = contact.numbers.first {
                        onDialNumber(single)
                    } else if contact.numbers.count > 1 {
                        withAnimation(.easeInOut(duration: 0.15)) { isExpanded.toggle() }
                    }
                } label: {
                    HStack(spacing: 8) {
                        // Avatar Circle
                        ZStack {
                            Circle()
                                .fill(avatarColor(for: contact.name))
                                .frame(width: 26, height: 26)
                            Text(contact.initial)
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.white)
                        }

                        // Name + primary number or count
                        VStack(alignment: .leading, spacing: 1) {
                            Text(contact.name)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            if contact.numbers.count == 1, let single = contact.numbers.first {
                                Text(single)
                                    .font(.system(size: 10))
                                    .foregroundStyle(.secondary)
                            } else if contact.numbers.count > 1 {
                                Text("\(contact.numbers.count) phone numbers")
                                    .font(.system(size: 9))
                                    .foregroundStyle(Color.accentColor)
                            }
                        }

                        Spacer()
                    }
                }
                .buttonStyle(.plain)

                if contact.numbers.count == 1, let single = contact.numbers.first {
                    // Single number actions: Favourite and SMS (tapping SMS NEVER triggers a call)
                    Button {
                        onToggleFavourite(contact.name, single)
                    } label: {
                        Image(systemName: isFavourite(single) ? "star.fill" : "star")
                            .font(.system(size: 12))
                            .foregroundStyle(isFavourite(single) ? Color.yellow : Color.secondary.opacity(0.6))
                    }
                    .buttonStyle(.plain)
                    .help(isFavourite(single) ? "Remove from favourites" : "Add to favourites")

                    if let onSMSNumber = onSMSNumber {
                        Button {
                            onSMSNumber(single, contact.name)
                        } label: {
                            Image(systemName: "message.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(Color.accentColor)
                                .padding(4)
                                .background(Color.accentColor.opacity(0.12), in: Circle())
                        }
                        .buttonStyle(.plain)
                        .help("Send SMS to \(contact.name)")
                    }
                } else if contact.numbers.count > 1 {
                    // Expand/collapse button for multi-number contacts
                    Button {
                        withAnimation(.easeInOut(duration: 0.15)) { isExpanded.toggle() }
                    } label: {
                        Image(systemName: isExpanded ? "chevron.up.circle.fill" : "chevron.down.circle")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.secondary)
                    }
                    .buttonStyle(.plain)
                    .help(isExpanded ? "Collapse numbers" : "Choose phone number")
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: 6)
                    .fill(isHovered ? Color.primary.opacity(0.06) : Color.clear)
            )
            .onHover { isHovered = $0 }

            // Expanded multi-number sub-list
            if isExpanded && contact.numbers.count > 1 {
                VStack(spacing: 2) {
                    ForEach(contact.numbers, id: \.self) { num in
                        HStack(spacing: 8) {
                            Button {
                                onDialNumber(num)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "phone")
                                        .font(.system(size: 9))
                                        .foregroundStyle(.secondary)
                                    Text(num)
                                        .font(.system(size: 11))
                                        .foregroundStyle(.primary)
                                    Spacer()
                                }
                            }
                            .buttonStyle(.plain)
                            .help("Dial \(num)")

                            Button {
                                onToggleFavourite(contact.name, num)
                            } label: {
                                Image(systemName: isFavourite(num) ? "star.fill" : "star")
                                    .font(.system(size: 11))
                                    .foregroundStyle(isFavourite(num) ? Color.yellow : Color.secondary.opacity(0.6))
                            }
                            .buttonStyle(.plain)
                            .help(isFavourite(num) ? "Remove from favourites" : "Add to favourites")

                            if let onSMSNumber = onSMSNumber {
                                Button {
                                    onSMSNumber(num, contact.name)
                                } label: {
                                    Image(systemName: "message.fill")
                                        .font(.system(size: 9))
                                        .foregroundStyle(Color.accentColor)
                                        .padding(3)
                                        .background(Color.accentColor.opacity(0.12), in: Circle())
                                }
                                .buttonStyle(.plain)
                                .help("Send SMS to \(num)")
                            }
                        }
                        .padding(.leading, 34)
                        .padding(.trailing, 8)
                        .padding(.vertical, 3)
                        .background(Color.primary.opacity(0.03), in: RoundedRectangle(cornerRadius: 4))
                    }
                }
                .padding(.bottom, 2)
            }
        }
    }

    private func avatarColor(for name: String) -> Color {
        let colors: [Color] = [.blue, .purple, .pink, .orange, .green, .teal, .indigo]
        let hash = abs(name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) })
        return colors[hash % colors.count]
    }
}

// MARK: - Favourite Contact Chip

/// Larger, full-width hit target for a favourite in the two-column call grid.
private struct FavouriteContactTile: View {
    let contact: FavouriteContact
    let onTap: () -> Void
    var onSMS: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 6) {
            Button(action: onTap) {
                HStack(spacing: 8) {
                    ZStack {
                        Circle()
                            .fill(LinearGradient(
                                colors: [Color.accentColor.opacity(0.85), Color.accentColor.opacity(0.55)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ))
                            .frame(width: 36, height: 36)
                        Text(contact.initial)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(contact.name)
                            .font(.system(size: 12, weight: .semibold))
                            .lineLimit(1)
                        Text(contact.number)
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Call \(contact.name), \(contact.number)")
            .help("Call \(contact.name)")

            if let onSMS = onSMS {
                Button(action: onSMS) {
                    Image(systemName: "message.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .padding(5)
                        .background(Color.accentColor.opacity(0.12), in: Circle())
                }
                .buttonStyle(.plain)
                .help("Send SMS to \(contact.name)")
                .accessibilityLabel("SMS \(contact.name)")
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .background(Color.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct FavouriteContactChip: View {
    let contact: FavouriteContact
    let onTap: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                // Avatar circle with initial
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: avatarGradient(for: contact.name),
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 36, height: 36)
                    Text(contact.initial)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .overlay(
                    Circle().stroke(.white.opacity(0.15), lineWidth: 0.5)
                )
                .scaleEffect(isHovered ? 1.08 : 1.0)

                Text(contact.name)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .frame(maxWidth: 56)
            }
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .animation(.spring(response: 0.2), value: isHovered)
        .accessibilityLabel("Call \(contact.name) at \(contact.number)")
    }

    private func avatarGradient(for name: String) -> [Color] {
        let palettes: [[Color]] = [
            [Color(hue: 0.58, saturation: 0.7, brightness: 0.8), Color(hue: 0.62, saturation: 0.6, brightness: 0.6)],
            [Color(hue: 0.32, saturation: 0.6, brightness: 0.7), Color(hue: 0.38, saturation: 0.5, brightness: 0.5)],
            [Color(hue: 0.75, saturation: 0.6, brightness: 0.8), Color(hue: 0.80, saturation: 0.5, brightness: 0.6)],
            [Color(hue: 0.05, saturation: 0.7, brightness: 0.8), Color(hue: 0.10, saturation: 0.6, brightness: 0.6)],
            [Color(hue: 0.50, saturation: 0.6, brightness: 0.7), Color(hue: 0.55, saturation: 0.5, brightness: 0.5)],
            [Color(hue: 0.90, saturation: 0.6, brightness: 0.8), Color(hue: 0.95, saturation: 0.5, brightness: 0.6)],
        ]
        let hash = abs(name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) })
        return palettes[hash % palettes.count]
    }
}

// MARK: - Add Favourite Sheet (With Contact Selection & Manual Fallback)

struct AddFavouriteSheet: View {
    @ObservedObject var store: FavouriteContactsStore
    @Environment(\.dismiss) private var dismiss

    @State private var mode: AddFavouriteMode = .selectContact
    @State private var searchQuery = ""
    @State private var selectedContact: SyncedContact?
    @State private var selectedNumber: String = ""
    @State private var manualName = ""
    @State private var manualNumber = ""

    enum AddFavouriteMode: String, CaseIterable {
        case selectContact = "From Contacts"
        case manual = "Manual Entry"
    }

    var canSave: Bool {
        switch mode {
        case .selectContact:
            return selectedContact != nil && !selectedNumber.isEmpty
        case .manual:
            return !manualName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                   !manualNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Header
            HStack {
                Label("Add Favourite Contact", systemImage: "star.badge.plus")
                    .font(.headline)
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                        .font(.title3)
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.escape, modifiers: [])
            }

            if !store.syncedContacts.isEmpty {
                Picker("", selection: $mode) {
                    ForEach(AddFavouriteMode.allCases, id: \.self) {
                        Text($0.rawValue).tag($0)
                    }
                }
                .pickerStyle(.segmented)
            }

            Divider()

            switch mode {
            case .selectContact:
                contactSelectionView
            case .manual:
                manualEntryView
            }

            Spacer()

            // Actions
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                Button("Add to Favourites") {
                    save()
                }
                .keyboardShortcut(.return, modifiers: [])
                .disabled(!canSave)
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(18)
        .frame(width: 340, height: 350)
        .onAppear {
            if store.syncedContacts.isEmpty {
                mode = .manual
            }
        }
    }

    // MARK: - Contact Selection Mode
    @ViewBuilder
    private var contactSelectionView: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Search field
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                TextField("Search phone contacts…", text: $searchQuery)
                    .font(.caption)
                    .textFieldStyle(.plain)
            }
            .padding(6)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 6))

            let list = store.filteredContacts(query: searchQuery)
            if list.isEmpty {
                Text(store.syncedContacts.isEmpty ? "No contacts synced. Switch to Manual Entry or sync contacts." : "No contacts match search.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 20)
            } else {
                ScrollView(.vertical, showsIndicators: true) {
                    LazyVStack(spacing: 4) {
                        ForEach(list) { c in
                            HStack {
                                Text(c.name)
                                    .font(.system(size: 12, weight: .medium))
                                    .lineLimit(1)
                                Spacer()
                                if c.numbers.count == 1, let num = c.numbers.first {
                                    Text(num)
                                        .font(.system(size: 10))
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text("\(c.numbers.count) numbers")
                                        .font(.system(size: 9))
                                        .foregroundStyle(Color.accentColor)
                                }
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(
                                RoundedRectangle(cornerRadius: 6)
                                    .fill(selectedContact?.id == c.id ? Color.accentColor.opacity(0.15) : Color.clear)
                            )
                            .contentShape(Rectangle())
                            .onTapGesture {
                                selectedContact = c
                                if c.numbers.count == 1, let first = c.numbers.first {
                                    selectedNumber = first
                                } else {
                                    selectedNumber = c.numbers.first ?? ""
                                }
                            }
                        }
                    }
                }
                .frame(maxHeight: 110)
            }

            // If contact with multiple numbers selected, let user pick the number
            if let sel = selectedContact {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Selected: \(sel.name)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.accentColor)

                    if sel.numbers.count > 1 {
                        Text("Choose phone number:")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Picker("", selection: $selectedNumber) {
                            ForEach(sel.numbers, id: \.self) { num in
                                Text(num).tag(num)
                            }
                        }
                        .pickerStyle(.menu)
                    } else if let only = sel.numbers.first {
                        Text(only)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(8)
                .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    // MARK: - Manual Entry Mode
    @ViewBuilder
    private var manualEntryView: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Name")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("e.g. MadamJi", text: $manualName)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Phone Number")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    TextField("e.g. +91 9876543210", text: $manualNumber)
                        .textFieldStyle(.roundedBorder)
                    Button {
                        if let clip = NSPasteboard.general.string(forType: .string) {
                            manualNumber = clip.trimmingCharacters(in: .whitespacesAndNewlines)
                        }
                    } label: {
                        Image(systemName: "doc.on.clipboard")
                            .foregroundStyle(Color.accentColor)
                    }
                    .buttonStyle(.plain)
                    .help("Paste from clipboard")
                }
            }
        }
    }

    private func save() {
        switch mode {
        case .selectContact:
            if let sel = selectedContact, !selectedNumber.isEmpty {
                store.add(name: sel.name, number: selectedNumber)
                dismiss()
            }
        case .manual:
            if canSave {
                store.add(name: manualName, number: manualNumber)
                dismiss()
            }
        }
    }
}

// MARK: - Manage Favourite Contacts (for Settings)

struct ManageFavouriteContactsView: View {
    @ObservedObject var store: FavouriteContactsStore
    @State private var showAdd = false
    @State private var editingContact: FavouriteContact?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Favourite Contacts (\(store.contacts.count)/30)")
                    .font(.subheadline)
                    .fontWeight(.medium)
                Spacer()
                Button { showAdd = true } label: {
                    Label("Add", systemImage: "plus")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            if store.contacts.isEmpty {
                Text("No favourite contacts yet.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                List {
                    ForEach(store.contacts) { contact in
                        HStack {
                            Text(contact.name).font(.body)
                            Spacer()
                            Text(contact.number).font(.caption).foregroundStyle(.secondary)
                            Button { editingContact = contact } label: {
                                Image(systemName: "pencil")
                            }
                            .buttonStyle(.plain)
                            Button(role: .destructive) {
                                store.remove(id: contact.id)
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .onDelete { indexSet in
                        for index in indexSet {
                            store.remove(id: store.contacts[index].id)
                        }
                    }
                }
                .frame(height: 140)
            }
        }
        .sheet(isPresented: $showAdd) {
            AddFavouriteSheet(store: store)
        }
    }
}

// MARK: - SMS Composer Sheet

struct SMSTarget: Identifiable {
    let id = UUID()
    let name: String
    let number: String
}

enum SMSSendState: Equatable {
    case editing
    case sending
    case sent
    case failed(String)
}

struct SMSComposerSheet: View {
    let target: SMSTarget
    let onSend: (String, String, @escaping (Result<Void, SmsError>) -> Void) -> Void
    let onDismiss: () -> Void

    @State private var messageText: String = ""
    @State private var sendState: SMSSendState = .editing

    private var charCount: Int { messageText.count }
    private var segmentCount: Int {
        if charCount == 0 { return 1 }
        return charCount <= 160 ? 1 : Int(ceil(Double(charCount) / 153.0))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack(spacing: 8) {
                Image(systemName: "message.fill")
                    .foregroundStyle(Color.accentColor)
                    .font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text("New Message to \(target.name)")
                        .font(.headline)
                        .lineLimit(1)
                    Text(target.number)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    onDismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                        .font(.title3)
                }
                .buttonStyle(.plain)
                .disabled(sendState == .sending)
            }

            Divider()

            // State-dependent content
            switch sendState {
            case .editing, .sending:
                VStack(alignment: .trailing, spacing: 6) {
                    TextEditor(text: $messageText)
                        .font(.body)
                        .frame(minHeight: 80, maxHeight: 110)
                        .padding(6)
                        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(Color.primary.opacity(0.1), lineWidth: 1)
                        )
                        .disabled(sendState == .sending)

                    HStack {
                        if sendState == .sending {
                            HStack(spacing: 6) {
                                ProgressView().controlSize(.small)
                                Text("Sending via Android…")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text("\(charCount) chars · \(segmentCount) SMS")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }

            case .sent:
                VStack(spacing: 10) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(.green)
                    Text("Message Sent via Android")
                        .font(.headline)
                    Text("Delivered to your phone's cellular network.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, minHeight: 90)

            case let .failed(error):
                VStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(.red)
                    Text("Failed to Send SMS")
                        .font(.headline)
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }
                .frame(maxWidth: .infinity, minHeight: 90)
            }

            // Bottom Actions
            HStack {
                switch sendState {
                case .editing:
                    Button("Cancel") { onDismiss() }
                        .keyboardShortcut(.escape, modifiers: [])
                        .buttonStyle(.bordered)
                        .controlSize(.regular)
                    Spacer()
                    Button("Send SMS") {
                        performSend()
                    }
                    .keyboardShortcut(.return, modifiers: [])
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                    .disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                case .sending:
                    Spacer()
                    Button("Sending…") {}
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                        .disabled(true)

                case .sent:
                    Spacer()
                    Button("Done") { onDismiss() }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                        .keyboardShortcut(.return, modifiers: [])

                case .failed:
                    Button("Cancel") { onDismiss() }
                        .buttonStyle(.bordered)
                        .controlSize(.regular)
                    Spacer()
                    Button("Retry") {
                        sendState = .editing
                        performSend()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                    .keyboardShortcut(.return, modifiers: [])
                }
            }
            .padding(.top, 2)
        }
        .padding(14)
    }

    private func performSend() {
        let trimmed = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        sendState = .sending
        onSend(target.number, trimmed) { result in
            DispatchQueue.main.async {
                switch result {
                case .success:
                    sendState = .sent
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                        onDismiss()
                    }
                case let .failure(error):
                    sendState = .failed(error.localizedDescription)
                }
            }
        }
    }
}

