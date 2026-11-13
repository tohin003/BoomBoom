import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI
import UIKit

/// The "Up next" tab: the whole queue, the playing entry marked, drag handles to reorder, swipe to remove.
struct UpNextView: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        let player = services.player
        VStack(spacing: 0) {
            header(player)
            if player.queue.isEmpty {
                PanelMessage(systemImage: "music.note.list", text: "Nothing queued")
            } else {
                QueueList(
                    player: player, entries: player.queue, currentID: player.currentEntryID)
                    .ignoresSafeArea(edges: .bottom)
            }
        }
    }

    private func header(_ player: PlayerStore) -> some View {
        VStack(spacing: 2) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Playing from")
                        .font(BoomFont.caption)
                        .foregroundStyle(BoomColor.textTertiary)
                    Text(player.source?.title ?? "Your queue")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                if !player.upNext.isEmpty {
                    Button("Clear") { withAnimation { player.clearUpNext() } }
                        .font(BoomFont.button)
                        .foregroundStyle(BoomColor.textPrimary)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 32)
                        .background(BoomColor.chip, in: Capsule())
                        .frame(minHeight: BoomMetrics.minimumTarget)
                        .accessibilityLabel("Clear up next")
                }
            }
            Toggle(isOn: Binding(get: { player.isAutoplayEnabled }, set: { player.isAutoplayEnabled = $0 })) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Autoplay")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    Text("Similar songs play when the queue ends")
                        .font(BoomFont.caption)
                        .foregroundStyle(BoomColor.textTertiary)
                }
            }
            .tint(BoomColor.accent)
            .frame(minHeight: BoomMetrics.minimumTarget)
        }
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.vertical, 8)
    }
}

/// A row of the queue. Kept small: hundreds of these scroll by.
private struct QueueRow: View {
    let song: Song
    let isCurrent: Bool
    let isPast: Bool
    let player: PlayerStore

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                ArtworkView(song.artwork, width: 48)
                if isCurrent {
                    Color.black.opacity(0.55)
                        .clipShape(.rect(cornerRadius: BoomMetrics.coverRadius))
                    CurrentBars(player: player)
                        .frame(width: 18, height: 16)
                }
            }
            .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title)
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(isCurrent ? BoomColor.accent : BoomColor.textPrimary)
                    .lineLimit(1)
                Text(song.artistLine)
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let duration = song.duration {
                Text(boomTime(duration))
                    .font(BoomFont.time)
                    .foregroundStyle(BoomColor.textTertiary)
            }
        }
        .opacity(isPast && !isCurrent ? 0.5 : 1)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isCurrent ? [.isSelected, .isButton] : .isButton)
        .accessibilityHint(isCurrent ? "Playing now" : "Plays this song")
        .accessibilityIdentifier("queue.row")
    }
}

private struct CurrentBars: View {
    let player: PlayerStore

    var body: some View {
        PlayingBars(isPlaying: player.status == .playing)
    }
}

/// The queue as a UIKit list: real drag handles, swipe actions, and smooth with hundreds of entries.
private struct QueueList: UIViewRepresentable {
    let player: PlayerStore
    let entries: [QueueEntry]
    let currentID: UUID?

    func makeCoordinator() -> Coordinator { Coordinator(player: player) }

    func makeUIView(context: Context) -> UICollectionView {
        context.coordinator.makeView()
    }

    func updateUIView(_ view: UICollectionView, context: Context) {
        context.coordinator.player = player
        context.coordinator.apply(entries: entries, currentID: currentID)
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDelegate {
        var player: PlayerStore
        private var dataSource: UICollectionViewDiffableDataSource<Int, UUID>?
        private weak var collectionView: UICollectionView?
        private var rows: [UUID: Row] = [:]
        private var stage = Stage.empty

        private enum Stage {
            /// Nothing shown yet: the first rows appear without animation.
            case empty
            /// Rows shown; the list still has to scroll to the playing entry once it is among them.
            case waitingForCurrent
            case settled
        }

        private struct Row: Equatable {
            var song: Song
            var isCurrent: Bool
            var isPast: Bool
        }

        init(player: PlayerStore) {
            self.player = player
        }

        func makeView() -> UICollectionView {
            var configuration = UICollectionLayoutListConfiguration(appearance: .plain)
            configuration.backgroundColor = .clear
            configuration.showsSeparators = false
            configuration.trailingSwipeActionsConfigurationProvider = { [weak self] indexPath in
                self?.swipeActions(at: indexPath)
            }
            let layout = UICollectionViewCompositionalLayout.list(using: configuration)
            let view = UICollectionView(frame: .zero, collectionViewLayout: layout)
            view.backgroundColor = .clear
            view.delegate = self
            view.contentInset.bottom = 24
            collectionView = view

            let registration = UICollectionView.CellRegistration<UICollectionViewListCell, UUID> {
                [weak self] cell, _, id in
                guard let self, let row = self.rows[id] else { return }
                let player = self.player
                cell.contentConfiguration = UIHostingConfiguration {
                    QueueRow(song: row.song, isCurrent: row.isCurrent, isPast: row.isPast, player: player)
                }
                .margins(.horizontal, BoomMetrics.margin)
                .margins(.vertical, 6)
                cell.backgroundConfiguration = .clear()
                cell.accessories = [
                    .reorder(
                        displayed: .always,
                        options: .init(
                            isHidden: false, reservedLayoutWidth: .standard,
                            tintColor: UIColor.white.withAlphaComponent(0.45), showsVerticalSeparator: false)),
                ]
            }
            let dataSource = UICollectionViewDiffableDataSource<Int, UUID>(collectionView: view) {
                collectionView, indexPath, id in
                collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: id)
            }
            dataSource.reorderingHandlers.canReorderItem = { _ in true }
            dataSource.reorderingHandlers.didReorder = { [weak self] transaction in
                self?.didReorder(transaction)
            }
            self.dataSource = dataSource
            return view
        }

        func apply(entries: [QueueEntry], currentID: UUID?) {
            guard let dataSource else { return }
            let currentIndex = entries.firstIndex { $0.id == currentID }
            var next: [UUID: Row] = [:]
            next.reserveCapacity(entries.count)
            for (index, entry) in entries.enumerated() {
                next[entry.id] = Row(
                    song: entry.song, isCurrent: entry.id == currentID,
                    isPast: currentIndex.map { index < $0 } ?? false)
            }
            let ids = entries.map(\.id)
            let changed = ids.filter { id in rows[id].map { $0 != next[id] } ?? false }
            let isFirst = stage == .empty
            if isFirst { stage = .waitingForCurrent }
            rows = next

            var snapshot = NSDiffableDataSourceSnapshot<Int, UUID>()
            snapshot.appendSections([0])
            snapshot.appendItems(ids)
            snapshot.reconfigureItems(changed)
            dataSource.apply(snapshot, animatingDifferences: !isFirst)

            if stage == .waitingForCurrent, let currentIndex {
                stage = .settled
                Task { @MainActor [weak self] in
                    guard let view = self?.collectionView, currentIndex < view.numberOfItems(inSection: 0) else {
                        return
                    }
                    view.layoutIfNeeded()
                    view.scrollToItem(at: IndexPath(item: currentIndex, section: 0), at: .top, animated: false)
                }
            }
        }

        private func didReorder(_ transaction: NSDiffableDataSourceTransaction<Int, UUID>) {
            var from: Int?
            var to: Int?
            for change in transaction.difference {
                switch change {
                case .remove(let offset, _, _): from = offset
                case .insert(let offset, _, _): to = offset
                }
            }
            guard let from, let to, from != to else { return }
            player.moveQueue(fromOffsets: IndexSet(integer: from), toOffset: to > from ? to + 1 : to)
        }

        private func swipeActions(at indexPath: IndexPath) -> UISwipeActionsConfiguration? {
            guard let id = dataSource?.itemIdentifier(for: indexPath), rows[id]?.isCurrent == false else {
                return nil
            }
            let remove = UIContextualAction(style: .destructive, title: "Remove") { [weak self] _, _, done in
                self?.player.removeFromQueue([id])
                done(true)
            }
            remove.image = UIImage(systemName: "minus.circle")
            remove.backgroundColor = UIColor(BoomColor.destructive)
            return UISwipeActionsConfiguration(actions: [remove])
        }

        func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
            collectionView.deselectItem(at: indexPath, animated: true)
            guard let id = dataSource?.itemIdentifier(for: indexPath), rows[id]?.isCurrent == false else { return }
            player.jump(to: id)
        }

        func collectionView(
            _ collectionView: UICollectionView, contextMenuConfigurationForItemsAt indexPaths: [IndexPath],
            point: CGPoint
        ) -> UIContextMenuConfiguration? {
            guard let indexPath = indexPaths.first, let id = dataSource?.itemIdentifier(for: indexPath),
                  let row = rows[id], !row.isCurrent
            else { return nil }
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
                UIMenu(children: [
                    UIAction(title: "Play now", image: UIImage(systemName: "play")) { _ in
                        self?.player.jump(to: id)
                    },
                    UIAction(title: "Play next", image: UIImage(systemName: "text.line.first.and.arrowtriangle.forward")) { _ in
                        guard let self, let target = self.player.currentIndex,
                              let source = self.player.queue.firstIndex(where: { $0.id == id }) else { return }
                        self.player.moveQueue(fromOffsets: IndexSet(integer: source), toOffset: target + 1)
                    },
                    UIAction(
                        title: "Remove from queue", image: UIImage(systemName: "minus.circle"), attributes: .destructive
                    ) { _ in
                        self?.player.removeFromQueue([id])
                    },
                ])
            }
        }
    }
}
