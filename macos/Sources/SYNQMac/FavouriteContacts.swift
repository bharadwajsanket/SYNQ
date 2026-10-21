import Foundation

struct FavouriteContact: Identifiable, Codable, Equatable, Hashable {
    var id: String
    var name: String
    var number: String   // always stored in normalised form

    init(name: String, number: String, id: String? = nil) {
        self.id = id ?? UUID().uuidString.lowercased()
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.number = number.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Display-safe initial (first non-whitespace character, uppercased)
    var initial: String {
        String(name.first.map(String.init)?.uppercased() ?? "#")
    }
}

/// A contact explicitly synced or shared from the paired Android device, held in memory only.
struct SyncedContact: Identifiable, Equatable, Hashable {
    var id: String { "\(name)|\(numbers.joined(separator: ","))" }
    var name: String
    var numbers: [String]

    var initial: String {
        String(name.first.map(String.init)?.uppercased() ?? "#")
    }
}

@MainActor
final class FavouriteContactsStore: ObservableObject {
    @Published private(set) var contacts: [FavouriteContact] = []
    @Published private(set) var deviceFavourites: [FavouriteContact] = []
    @Published private(set) var syncedContacts: [SyncedContact] = []
    @Published var isSyncing: Bool = false
    @Published var syncStatus: String? = nil

    private let defaults = UserDefaults.standard
    private let favouritesKey = "settings.favouriteContacts.v1"
    private let legacySyncedKey = "settings.syncedContacts.v1"

    /// Called whenever the local favourites list is mutated so that the change
    /// can be forwarded to the connected Android device.
    var onFavouritesChanged: (([FavouriteContact]) -> Void)?

    var allFavourites: [FavouriteContact] {
        contacts
    }

    init() {
        loadFavourites()
        // Never persist full address book on Mac storage; purge any legacy entries
        defaults.removeObject(forKey: legacySyncedKey)
    }

    // MARK: – Favourites Mutations

    func add(name: String, number: String) {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNumber = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty, !trimmedNumber.isEmpty, contacts.count < 30 else { return }

        let cleanNewNumber = normalizeNumber(trimmedNumber)
        let isDuplicate = contacts.contains {
            $0.name.caseInsensitiveCompare(trimmedName) == .orderedSame &&
            normalizeNumber($0.number) == cleanNewNumber
        }

        if !isDuplicate {
            contacts.append(FavouriteContact(name: trimmedName, number: trimmedNumber))
            deviceFavourites = contacts
            saveFavourites()
        }
    }

    func update(id: String, name: String, number: String) {
        guard let i = contacts.firstIndex(where: { $0.id == id }) else { return }
        contacts[i].name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        contacts[i].number = number.trimmingCharacters(in: .whitespacesAndNewlines)
        deviceFavourites = contacts
        saveFavourites()
    }

    func remove(id: String) {
        contacts.removeAll { $0.id == id }
        deviceFavourites.removeAll { $0.id == id }
        saveFavourites()
    }

    func move(from source: IndexSet, to destination: Int) {
        contacts.move(fromOffsets: source, toOffset: destination)
        deviceFavourites = contacts
        saveFavourites()
    }

    func isFavourite(number: String) -> Bool {
        let clean = normalizeNumber(number)
        return contacts.contains { normalizeNumber($0.number) == clean }
    }

    func toggleFavourite(name: String, number: String) {
        let clean = normalizeNumber(number)
        if let existing = contacts.first(where: { normalizeNumber($0.number) == clean }) {
            remove(id: existing.id)
        } else {
            add(name: name, number: number)
        }
    }

    private var syncTimeoutWorkItem: DispatchWorkItem?

    // MARK: – In-Memory Synced Contacts from Phone

    func startSync() {
        isSyncing = true
        syncTimeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.isSyncing else { return }
            self.isSyncing = false
            if self.syncedContacts.isEmpty {
                self.syncStatus = "Phone did not respond. Check connection and try again."
            }
        }
        syncTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 8.0, execute: item)
    }

    func setSyncedContacts(_ list: [SyncedContact]) {
        syncTimeoutWorkItem?.cancel()
        syncTimeoutWorkItem = nil
        self.syncedContacts = list
        self.isSyncing = false
        if list.isEmpty {
            self.syncStatus = "No contacts found on phone"
        } else {
            self.syncStatus = "\(list.count) contacts available"
        }
    }

    func setDeviceFavourites(_ list: [FavouriteContact], isInitialSync: Bool = false) {
        if isInitialSync && !contacts.isEmpty {
            var merged = list
            var changed = false
            for local in contacts {
                let clean = normalizeNumber(local.number)
                if !merged.contains(where: { normalizeNumber($0.number) == clean }) && merged.count < 30 {
                    merged.append(local)
                    changed = true
                }
            }
            contacts = merged
            deviceFavourites = merged
            if let data = try? JSONEncoder().encode(contacts) {
                defaults.set(data, forKey: favouritesKey)
            }
            if changed {
                onFavouritesChanged?(contacts)
            }
        } else {
            contacts = list
            deviceFavourites = list
            if let data = try? JSONEncoder().encode(contacts) {
                defaults.set(data, forKey: favouritesKey)
            }
        }
    }

    func setSyncError(_ message: String) {
        syncTimeoutWorkItem?.cancel()
        syncTimeoutWorkItem = nil
        self.isSyncing = false
        self.syncStatus = message
    }

    func filteredContacts(query: String) -> [SyncedContact] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if trimmed.isEmpty {
            return syncedContacts
        }
        return syncedContacts.filter { contact in
            contact.name.lowercased().contains(trimmed) ||
            contact.numbers.contains { $0.lowercased().contains(trimmed) }
        }
    }

    // MARK: – Persistence (Favourites Only)

    private func loadFavourites() {
        guard let data = defaults.data(forKey: favouritesKey),
              let decoded = try? JSONDecoder().decode([FavouriteContact].self, from: data) else { return }
        contacts = decoded
        deviceFavourites = decoded
    }

    private func saveFavourites() {
        deviceFavourites = contacts
        guard let data = try? JSONEncoder().encode(contacts) else { return }
        defaults.set(data, forKey: favouritesKey)
        onFavouritesChanged?(contacts)
    }

    private func normalizeNumber(_ num: String) -> String {
        num.filter { $0.isNumber || $0 == "+" }
    }
}
