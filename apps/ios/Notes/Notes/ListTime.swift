import Foundation

// Ported from the web app (apps/web/src/notes/list-time.ts) by way of Android's ListTime.kt.

private let day: Int64 = 86_400_000

/// Apple Notes-style row time: a clock time today, then Yesterday, a weekday, a short date.
func listTime(_ ts: Int64, now: Int64 = systemNow(), calendar: Calendar = .current) -> String {
    let at = Date(timeIntervalSince1970: Double(ts) / 1000)
    let today = Date(timeIntervalSince1970: Double(now) / 1000)
    if calendar.isDate(at, inSameDayAs: today) { return at.formatted(date: .omitted, time: .shortened) }
    if let yesterday = calendar.date(byAdding: .day, value: -1, to: today), calendar.isDate(at, inSameDayAs: yesterday)
    {
        return "Yesterday"
    }
    if now - ts < 7 * day { return at.formatted(.dateTime.weekday(.wide)) }
    return at.formatted(date: .numeric, time: .omitted)
}

struct DateGroup<T> {
    var label: String
    var items: [T]
}

/// Splits notes (newest first) into the list's date sections, skipping empty ones.
func groupByDate<T>(_ items: [T], updatedAt: (T) -> Int64, now: Int64 = systemNow(), calendar: Calendar = .current)
    -> [DateGroup<T>]
{
    let today = Date(timeIntervalSince1970: Double(now) / 1000)
    func label(_ ts: Int64) -> String {
        if calendar.isDate(Date(timeIntervalSince1970: Double(ts) / 1000), inSameDayAs: today) { return "Today" }
        if now - ts < 7 * day { return "Previous 7 days" }
        if now - ts < 30 * day { return "Previous 30 days" }
        return "Earlier"
    }
    var groups: [DateGroup<T>] = []
    for item in items {
        let l = label(updatedAt(item))
        if groups.last?.label == l {
            groups[groups.count - 1].items.append(item)
        } else {
            groups.append(DateGroup(label: l, items: [item]))
        }
    }
    return groups
}

func systemNow() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
