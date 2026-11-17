import SwiftUI

/// A segmented picker that turns into a full-width checkmark list when any label wouldn't fit on one
/// line, as with longer translations or larger text sizes. The check runs on every layout, so the same
/// screen can show segments in one language and a list in another. Android's `AdaptiveChoiceRow`
/// follows the same rule.
struct AdaptiveChoicePicker<Value: Hashable>: View {
    private let title: LocalizedStringKey
    @Binding private var selection: Value
    private let options: [Value]
    private let label: (Value) -> Text

    init(_ title: LocalizedStringKey, selection: Binding<Value>, options: [Value],
         label: @escaping (Value) -> Text) {
        self.title = title
        _selection = selection
        self.options = options
        self.label = label
    }

    var body: some View {
        // ViewThatFits compares each child's ideal width, and a segmented control's ideal width is
        // what its widest label needs in every segment, so the list wins whenever a label would be cut.
        ViewThatFits(in: .horizontal) {
            Picker(title, selection: $selection) {
                ForEach(options, id: \.self) { label($0).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            list
        }
    }

    private var list: some View {
        VStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                if index > 0 { Divider() }
                Button {
                    selection = option
                } label: {
                    HStack(spacing: 12) {
                        label(option)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "checkmark")
                            .fontWeight(.semibold)
                            .foregroundStyle(Color.accentColor)
                            .opacity(option == selection ? 1 : 0)
                            .accessibilityHidden(true)
                    }
                    .padding(.vertical, 10)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(option == selection ? .isSelected : [])
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title)
    }
}
