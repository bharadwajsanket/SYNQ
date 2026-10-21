import SwiftUI

// MARK: - Call Picker Panel

enum CallPickerTab: String, CaseIterable {
    case favourites = "Favourites"
    case allContacts = "All Contacts"
}

/// Expanded panel shown below the Call action button.
/// Shows favourite contacts and all contacts synced from the phone with search, multi-number selection, and quick-dial.
struct CallContactPickerView: View {
    @ObservedObject var favourites: FavouriteContactsStore
    let onDial: (String) -> Void
    let onManualDial: () -> Void
    var onSyncContacts: (() -> Void)? = nil
    let onClose: () -> Void

    @State private var selectedTab: CallPickerTab = .favourites
    @State private var manualNumber: String = ""
    @State private var searchQuery: String = ""
    @State private var showAddSheet = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Header
            HStack {
                Label("Call from Mac", systemImage: "phone.arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer()
                Button { onClose() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close call picker")
            }

            // Tab selector (Favourites / All Contacts)
            Picker("", selection: $selectedTab) {
                Text("Favourites (\(favourites.contacts.count))")
                    .tag(CallPickerTab.favourites)
                Text("All Contacts (\(favourites.syncedContacts.count))")
                    .tag(CallPickerTab.allContacts)
            }
            .pickerStyle(.segmented)

            Divider()

            switch selectedTab {
            case .favourites:
                favouritesSection
            case .allContacts:
                allContactsSection
            }

            Divider()

            // ── Manual entry fallback ─────────────────────────────────────
            HStack(spacing: 6) {
                Image(systemName: "number")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                TextField("Enter number or paste from clipboard", text: $manualNumber)
                    .font(.caption)
                    .textFieldStyle(.plain)
                    .onSubmit { dialManual() }
                if !manualNumber.isEmpty {
                    Button { dialManual() } label: {
                        Image(systemName: "phone.fill")
                            .foregroundStyle(Color.accentColor)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Dial")
                } else {
                    Button("Clipboard") { onManualDial() }
                        .buttonStyle(.plain)
                        .font(.caption)
                        .foregroundStyle(Color.accentColor)
                }
            }
            .padding(8)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(.white.opacity(0.08), lineWidth: 0.5)
        )
    }

    // MARK: - Favourites View
    @ViewBuilder
    private var favouritesSection: some View {
        if favourites.contacts.isEmpty {
            VStack(spacing: 6) {
                HStack(spacing: 6) {
                    Image(systemName: "star.slash")
                        .foregroundStyle(.tertiary)
                        .font(.caption)
                    Text("No favourites yet — select a contact below")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(favourites.contacts) { contact in
                        FavouriteContactChip(contact: contact) {
                            onDial(contact.number)
                        }
                    }
                }
                .padding(.horizontal, 2)
                .padding(.vertical, 2)
            }
        }

        // Add favourite button
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
            if filtered.isEmpty {
                VStack(spacing: 8) {
                    if favourites.syncedContacts.isEmpty {
                        Text("No contacts synced from phone yet.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
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
                                onDialNumber: { num in onDial(num) },
                                onToggleFavourite: { name, num in favourites.toggleFavourite(name: name, number: num) }
                            )
                        }
                    }
                }
                .frame(maxHeight: 180)
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
                    favourites.isSyncing = true
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
}

// MARK: - Synced Contact Row View (Supports Single & Multiple Numbers)

struct SyncedContactRowView: View {
    let contact: SyncedContact
    let isFavourite: (String) -> Bool
    let onDialNumber: (String) -> Void
    let onToggleFavourite: (String, String) -> Void

    @State private var isExpanded = false
    @State private var isHovered = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
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

                if contact.numbers.count == 1, let single = contact.numbers.first {
                    // Single number actions
                    Button {
                        onToggleFavourite(contact.name, single)
                    } label: {
                        Image(systemName: isFavourite(single) ? "star.fill" : "star")
                            .font(.system(size: 12))
                            .foregroundStyle(isFavourite(single) ? Color.yellow : Color.secondary.opacity(0.6))
                    }
                    .buttonStyle(.plain)
                    .help(isFavourite(single) ? "Remove from favourites" : "Add to favourites")

                    Button {
                        onDialNumber(single)
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(Color.accentColor)
                            .padding(5)
                            .background(Color.accentColor.opacity(0.12), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .help("Dial on Android")
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
            .contentShape(Rectangle())
            .onTapGesture {
                if contact.numbers.count == 1, let single = contact.numbers.first {
                    onDialNumber(single)
                } else if contact.numbers.count > 1 {
                    withAnimation(.easeInOut(duration: 0.15)) { isExpanded.toggle() }
                }
            }

            // Expanded multi-number sub-list
            if isExpanded && contact.numbers.count > 1 {
                VStack(spacing: 2) {
                    ForEach(contact.numbers, id: \.self) { num in
                        HStack(spacing: 8) {
                            Image(systemName: "phone")
                                .font(.system(size: 9))
                                .foregroundStyle(.secondary)
                            Text(num)
                                .font(.system(size: 11))
                                .foregroundStyle(.primary)
                            Spacer()
                            Button {
                                onToggleFavourite(contact.name, num)
                            } label: {
                                Image(systemName: isFavourite(num) ? "star.fill" : "star")
                                    .font(.system(size: 11))
                                    .foregroundStyle(isFavourite(num) ? Color.yellow : Color.secondary.opacity(0.6))
                            }
                            .buttonStyle(.plain)
                            .help(isFavourite(num) ? "Remove from favourites" : "Add to favourites")

                            Button {
                                onDialNumber(num)
                            } label: {
                                Image(systemName: "phone.fill")
                                    .font(.system(size: 10))
                                    .foregroundStyle(Color.accentColor)
                                    .padding(4)
                                    .background(Color.accentColor.opacity(0.12), in: Circle())
                            }
                            .buttonStyle(.plain)
                            .help("Dial \(num)")
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
                TextField("e.g. Sanket", text: $manualName)
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
