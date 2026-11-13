import BoomModels
import SwiftUI

/// A side-scrolling row of filter pills. The selected pill is white with black text.
///
///     ChipBar(page.chips, selection: selected, title: \.title) { chip in model.select(chip) }
public struct ChipBar<Item: Hashable>: View {
    private let items: [Item]
    private let selection: Item?
    private let title: (Item) -> String
    private let onSelect: (Item) -> Void

    public init(
        _ items: [Item], selection: Item?, title: @escaping (Item) -> String, onSelect: @escaping (Item) -> Void
    ) {
        self.items = items
        self.selection = selection
        self.title = title
        self.onSelect = onSelect
    }

    public var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(items, id: \.self) { item in
                    ChipPill(title(item), isSelected: item == selection) { onSelect(item) }
                }
            }
            .padding(.horizontal, BoomMetrics.margin)
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
    }
}

/// One filter pill.
public struct ChipPill: View {
    private let title: String
    private let isSelected: Bool
    private let action: () -> Void

    public init(_ title: String, isSelected: Bool, action: @escaping () -> Void) {
        self.title = title
        self.isSelected = isSelected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(title)
                .font(BoomFont.button)
                .lineLimit(1)
                .foregroundStyle(isSelected ? Color.black : BoomColor.textPrimary)
                .padding(.horizontal, 14)
                .frame(minHeight: 34)
                .background(
                    isSelected ? BoomColor.chipSelected : BoomColor.chip,
                    in: RoundedRectangle(cornerRadius: 8))
                .frame(minHeight: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .accessibilityIdentifier("chip")
    }
}
