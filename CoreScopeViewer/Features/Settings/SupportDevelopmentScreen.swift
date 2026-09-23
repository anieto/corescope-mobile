import Observation
import StoreKit
import SwiftUI

@MainActor
@Observable
final class DevelopmentTipStore {
    enum Tip: String, CaseIterable, Identifiable {
        case coffee = "com.btdev.nodescope.tip.coffee"
        case lunch = "com.btdev.nodescope.tip.lunch"
        case dinner = "com.btdev.nodescope.tip.dinner"

        var id: String { rawValue }

        var symbol: String {
            switch self {
            case .coffee: "cup.and.saucer.fill"
            case .lunch: "takeoutbag.and.cup.and.straw.fill"
            case .dinner: "fork.knife"
            }
        }
    }

    private(set) var products: [Product] = []
    private(set) var isLoading = false
    private(set) var purchasingProductID: String?
    private(set) var errorMessage: String?
    private(set) var thankYouMessage: String?

    func loadProducts() async {
        guard products.isEmpty, !isLoading else { return }
        isLoading = true
        errorMessage = nil

        do {
            let fetchedProducts = try await Product.products(for: Tip.allCases.map(\.rawValue))
            let order = Dictionary(uniqueKeysWithValues: Tip.allCases.enumerated().map { ($1.rawValue, $0) })
            products = fetchedProducts.sorted {
                order[$0.id, default: .max] < order[$1.id, default: .max]
            }
            if products.isEmpty {
                errorMessage = String(localized: "Tips are temporarily unavailable.")
            }
        } catch {
            errorMessage = String(localized: "Tips couldn't be loaded. Please try again.")
        }

        isLoading = false
        await finishPendingTips()
    }

    func purchase(_ product: Product, using purchase: PurchaseAction) async {
        guard purchasingProductID == nil else { return }
        purchasingProductID = product.id
        errorMessage = nil
        defer { purchasingProductID = nil }

        do {
            switch try await purchase(product) {
            case .success(.verified(let transaction)):
                await transaction.finish()
                thankYouMessage = String(localized: "Thank you for supporting NodeScope!")
            case .success(.unverified):
                errorMessage = String(localized: "The purchase couldn't be verified.")
            case .pending:
                errorMessage = String(localized: "The purchase is pending approval.")
            case .userCancelled:
                break
            @unknown default:
                errorMessage = String(localized: "The purchase couldn't be completed.")
            }
        } catch {
            errorMessage = String(localized: "The purchase couldn't be completed. Please try again.")
        }
    }

    func clearThankYouMessage() {
        thankYouMessage = nil
    }

    private func finishPendingTips() async {
        let tipIDs = Set(Tip.allCases.map(\.rawValue))
        for await result in Transaction.unfinished {
            guard case .verified(let transaction) = result,
                  tipIDs.contains(transaction.productID) else { continue }
            await transaction.finish()
        }
    }
}

struct SupportDevelopmentScreen: View {
    @Environment(\.purchase) private var purchase
    @State private var store = DevelopmentTipStore()

    var body: some View {
        List {
            SupportDevelopmentIntroduction()
            DevelopmentTipsSection(
                products: store.products,
                isLoading: store.isLoading,
                purchasingProductID: store.purchasingProductID,
                purchase: { product in
                    Task { await store.purchase(product, using: purchase) }
                }
            )

            if let errorMessage = store.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    if store.products.isEmpty && !store.isLoading {
                        Button("Try Again") {
                            Task { await store.loadProducts() }
                        }
                    }
                }
            }
        }
        .adaptiveContentWidth()
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .floatingDockScrollClearance()
        .navigationTitle("Support Development")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await store.loadProducts()
        }
        .alert(
            "Thank You!",
            isPresented: Binding(
                get: { store.thankYouMessage != nil },
                set: { isPresented in
                    if !isPresented { store.clearThankYouMessage() }
                }
            )
        ) {
            Button("Done", role: .cancel) {
                store.clearThankYouMessage()
            }
        } message: {
            if let thankYouMessage = store.thankYouMessage {
                Text(thankYouMessage)
            }
        }
    }
}

private struct SupportDevelopmentIntroduction: View {
    var body: some View {
        Section {
            VStack(spacing: 10) {
                Image(systemName: "heart.circle.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(NodeScopeStyle.activity)
                    .accessibilityHidden(true)
                Text("Enjoying NodeScope?")
                    .font(.title3.weight(.semibold))
                Text("If NodeScope has been useful, you can leave an optional tip to support its continued development.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .listRowBackground(Color.clear)
        }
    }
}

private struct DevelopmentTipsSection: View {
    let products: [Product]
    let isLoading: Bool
    let purchasingProductID: String?
    let purchase: (Product) -> Void

    var body: some View {
        Section {
            if isLoading && products.isEmpty {
                HStack {
                    Spacer()
                    ProgressView("Loading tips…")
                    Spacer()
                }
            } else {
                ForEach(products) { product in
                    let tip = DevelopmentTipStore.Tip(rawValue: product.id)
                    DevelopmentTipRow(
                        product: product,
                        title: product.displayName,
                        symbol: tip?.symbol ?? "heart.fill",
                        isPurchasing: purchasingProductID == product.id,
                        isDisabled: purchasingProductID != nil,
                        purchase: { purchase(product) }
                    )
                }
            }
        } header: {
            Text("Leave a Tip")
        } footer: {
            Text("Tips are one-time purchases. They don't unlock features or include goods, services, or membership benefits.")
        }
    }
}

private struct DevelopmentTipRow: View {
    let product: Product
    let title: String
    let symbol: String
    let isPurchasing: Bool
    let isDisabled: Bool
    let purchase: () -> Void

    var body: some View {
        Button(action: purchase) {
            DevelopmentTipRowLabel(
                title: title,
                symbol: symbol,
                displayPrice: product.displayPrice,
                isPurchasing: isPurchasing
            )
        }
        .disabled(isDisabled)
        .accessibilityHint("Makes a one-time tip purchase")
    }
}

private struct DevelopmentTipRowLabel: View {
    @Environment(\.colorScheme) private var colorScheme

    let title: String
    let symbol: String
    let displayPrice: String
    let isPurchasing: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(NodeScopeStyle.signal)
                .frame(width: 28)
                .accessibilityHidden(true)
            Text(title)
                .foregroundStyle(.primary)
            Spacer()
            if isPurchasing {
                ProgressView()
            } else {
                Text(displayPrice)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(NodeScopeStyle.signal.readableForeground(for: colorScheme))
            }
        }
        .contentShape(Rectangle())
    }
}
