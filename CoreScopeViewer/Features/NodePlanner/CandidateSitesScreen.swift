import SwiftUI

struct CandidateSitesScreen: View {
    @State private var model = CandidatePlansModel.shared
    @State private var editing: DeploymentPlan?
    @State private var deleting: DeploymentPlan?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                LearnLabel("Deployment plans", term: .deploymentPlan).font(.monaspace(.title2).bold())
                Text("Save proposed locations and equipment assumptions on this device. Coverage and potential-link analysis are not available here yet.")
                    .foregroundStyle(.secondary)
                if let error = model.error {
                    Text(error).foregroundStyle(.red)
                    Button("Reload plans") { Task { await model.reload() } }.disabled(model.isBusy)
                }
                if model.isBusy { ProgressView() }
                if model.isLoaded {
                    Button("New plan", systemImage: "plus") { editing = CandidatePlansModel.newPlan() }
                        .disabled(model.isBusy || model.plans.count >= 100)
                    if model.plans.isEmpty { Text("No saved plans. Create one, then add your candidate sites.") }
                    ForEach(model.plans) { plan in
                        CandidatePlanRow(plan: plan, open: { model.error = nil; editing = plan },
                                         delete: { deleting = plan })
                    }
                    .disabled(model.isBusy)
                }
            }
            .padding().frame(maxWidth: 760).frame(maxWidth: .infinity, alignment: .center)
        }
        .meshPageBackground()
        .navigationTitle("Candidate Sites")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
        .sheet(item: $editing) { plan in
            CandidatePlanEditor(initial: plan, model: model)
        }
        .confirmationDialog("Delete this plan and its saved sites?", isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } }
        ), titleVisibility: .visible) {
            Button("Delete plan", role: .destructive) {
                if let plan = deleting { Task { await model.delete(plan) } }
                deleting = nil
            }
        }
    }
}

private struct CandidatePlanRow: View {
    let plan: DeploymentPlan
    let open: () -> Void
    let delete: () -> Void
    var body: some View {
        HStack {
            Button(action: open) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(plan.name).font(.monaspace(.headline)).foregroundStyle(.primary)
                    Text("\(plan.sites.count) sites").font(.monaspace(.subheadline)).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
            Menu {
                Button("Open", action: open)
                Button("Delete plan", role: .destructive, action: delete)
            } label: { Image(systemName: "ellipsis").padding(12) }
                .accessibilityLabel("Actions for \(plan.name)")
        }.padding().instrumentCard()
    }
}

private struct CandidatePlanEditor: View {
    let initial: DeploymentPlan
    let model: CandidatePlansModel
    @Environment(\.dismiss) private var dismiss
    @State private var draft: DeploymentPlan
    @State private var siteEditor: CandidateSiteEditRequest?
    @State private var deleting: CandidateSite?
    @State private var confirmDiscard = false

    init(initial: DeploymentPlan, model: CandidatePlansModel) {
        self.initial = initial
        self.model = model
        _draft = State(initialValue: initial)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        LearnLabel("Deployment plan", term: .deploymentPlan).font(.monaspace(.headline))
                        TextField("Plan name", text: $draft.name).font(.monaspace(.body)).textFieldStyle(.roundedBorder)
                        Text("Changes are saved only when you choose Save plan.").font(.caption).foregroundStyle(.secondary)
                    }.padding().instrumentCard()
                    LearnLabel("Candidate sites", term: .candidateSite).font(.monaspace(.headline))
                    ForEach(draft.sites) { site in
                        CandidateEditorSiteRow(site: site, selected: draft.selectedSiteID == site.id,
                            edit: { siteEditor = CandidateSiteEditRequest(site: site) },
                            select: { draft.selectedSiteID = site.id },
                            duplicate: {
                                var copy = site
                                copy.id = UUID().uuidString.lowercased()
                                draft.sites.append(copy)
                                draft.selectedSiteID = copy.id
                            },
                            delete: { deleting = site }, canDuplicate: draft.sites.count < 100)
                    }
                    Button("Add site", systemImage: "mappin.and.ellipse") {
                        siteEditor = CandidateSiteEditRequest(site: nil)
                    }.disabled(draft.sites.count >= 100)
                    if let error = model.error { Text(error).foregroundStyle(.red) }
                    if model.isBusy { ProgressView("Saving…") }
                }.padding().frame(maxWidth: 760).frame(maxWidth: .infinity)
            }
            .meshPageBackground()
            .navigationTitle("Edit plan").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        if draft != initial { confirmDiscard = true } else { dismiss() }
                    }.disabled(model.isBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save plan") {
                        var saved = draft
                        saved.updatedAtMilliseconds = max(saved.createdAtMilliseconds, Int64(Date().timeIntervalSince1970 * 1000))
                        Task { if await model.save(saved) { dismiss() } }
                    }.disabled(model.isBusy || (try? draft.validate()) == nil)
                }
            }
            .disabled(model.isBusy)
            .sheet(item: $siteEditor) { request in
                CandidateSiteEditor(site: request.site) { site in
                    if let index = draft.sites.firstIndex(where: { $0.id == site.id }) { draft.sites[index] = site }
                    else { draft.sites.append(site) }
                    draft.selectedSiteID = site.id
                }
            }
            .confirmationDialog("Discard unsaved changes?", isPresented: $confirmDiscard, titleVisibility: .visible) {
                Button("Discard changes", role: .destructive) { dismiss() }
                Button("Keep editing", role: .cancel) {}
            }
            .confirmationDialog("Remove this site from the plan?", isPresented: Binding(
                get: { deleting != nil }, set: { if !$0 { deleting = nil } }
            ), titleVisibility: .visible) {
                Button("Remove site", role: .destructive) {
                    if let site = deleting {
                        draft.sites.removeAll { $0.id == site.id }
                        if draft.selectedSiteID == site.id { draft.selectedSiteID = draft.sites.first?.id }
                    }
                    deleting = nil
                }
            }
        }
        .interactiveDismissDisabled(draft != initial || model.isBusy)
    }
}

private struct CandidateEditorSiteRow: View {
    let site: CandidateSite
    let selected: Bool
    let edit: () -> Void
    let select: () -> Void
    let duplicate: () -> Void
    let delete: () -> Void
    let canDuplicate: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(site.name).font(.monaspace(.headline))
                Spacer()
                if selected { Image(systemName: "checkmark.circle.fill").accessibilityLabel("Selected site") }
            }
            Text("\(site.latitude.formatted()), \(site.longitude.formatted())")
                .font(.monaspace(.subheadline)).foregroundStyle(.secondary)
            ViewThatFits(in: .horizontal) {
                HStack {
                    Button("Edit", action: edit)
                    Button("Select", action: select).disabled(selected)
                    Menu("More") {
                        Button("Duplicate", action: duplicate).disabled(!canDuplicate)
                        Button("Remove site", role: .destructive, action: delete)
                    }
                }.buttonStyle(.bordered)
                VStack(alignment: .leading) {
                    Button("Edit", action: edit)
                    Button("Select", action: select).disabled(selected)
                    Button("Duplicate", action: duplicate).disabled(!canDuplicate)
                    Button("Remove site", role: .destructive, action: delete)
                }
            }
        }.padding().instrumentCard()
    }
}

private struct CandidateSiteEditRequest: Identifiable {
    let id = UUID()
    let site: CandidateSite?
}

private struct CandidateSiteEditor: View {
    let site: CandidateSite?
    let apply: (CandidateSite) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var values: [String]
    @State private var error: LocalizedStringResource?
    @State private var confirmDiscard = false
    @State private var showsMap = false
    private let originalName: String
    private let originalValues: [String]

    init(site: CandidateSite?, apply: @escaping (CandidateSite) -> Void) {
        self.site = site
        self.apply = apply
        let fields = [site?.latitude, site?.longitude, site?.assumptions.antennaHeightMeters,
                      site?.assumptions.frequencyMHz, site?.assumptions.transmitPowerDBm,
                      site?.assumptions.antennaGainDBi, site?.assumptions.cableLossDB, site?.assumptions.additionalLossDB]
        let text = fields.map { $0.map { String($0) } ?? "" }
        originalName = site?.name ?? ""
        originalValues = text
        _name = State(initialValue: site?.name ?? "")
        _values = State(initialValue: text)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    TextField("Site name", text: $name).font(.monaspace(.body)).textFieldStyle(.roundedBorder)
                    Text("Coordinates are required. Leave unknown equipment values blank. Use meters and decimal degrees; a minus sign means south or west.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Button("Choose on map", systemImage: "map") { showsMap = true }
                    ForEach(CandidateInputField.allCases) { field in
                        CandidateNumericField(field: field, value: $values[field.rawValue])
                    }
                    if let error { Text(error).foregroundStyle(.red) }
                }.padding().frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
            .meshPageBackground()
            .navigationTitle(site == nil ? "Add site" : "Edit site").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if name != originalName || values != originalValues { confirmDiscard = true } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        do {
                            let result = try CandidateSiteInput.make(id: site?.id ?? UUID().uuidString.lowercased(),
                                                                     name: name, values: values)
                            apply(result)
                            dismiss()
                        } catch {
                            self.error = "Check the site name, coordinates, and displayed numeric ranges. Try a shorter name if needed. Optional fields may be blank."
                        }
                    }
                }
            }
            .sheet(isPresented: $showsMap) {
                CandidateSiteMapPicker(
                    latitude: Double(values[0].trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")),
                    longitude: Double(values[1].trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: "."))
                ) { latitude, longitude in
                    values[0] = String(latitude)
                    values[1] = String(longitude)
                }
            }
            .confirmationDialog("Discard site changes?", isPresented: $confirmDiscard, titleVisibility: .visible) {
                Button("Discard changes", role: .destructive) { dismiss() }
                Button("Keep editing", role: .cancel) {}
            }
        }.interactiveDismissDisabled(name != originalName || values != originalValues)
    }
}

private enum CandidateInputField: Int, CaseIterable, Identifiable {
    case latitude, longitude, height, frequency, power, gain, cable, additional
    var id: Int { rawValue }
    var title: LocalizedStringKey {
        switch self {
        case .latitude: "Latitude (−90 to 90)"
        case .longitude: "Longitude (−180 to 180)"
        case .height: "Antenna height (m, 0–10000)"
        case .frequency: "Frequency (MHz, 0.001–100000)"
        case .power: "Transmit power (dBm, −200–100)"
        case .gain: "Antenna gain (dBi, −100–100)"
        case .cable: "Cable loss (dB, 0–200)"
        case .additional: "Additional loss (dB, 0–200)"
        }
    }
    var term: GlossaryTerm {
        switch self {
        case .latitude, .longitude: .planningCoordinates
        case .height: .antennaHeight
        case .frequency: .radioFrequency
        case .power: .transmitPower
        case .gain: .antennaGain
        case .cable: .cableLoss
        case .additional: .additionalLoss
        }
    }
}

private struct CandidateNumericField: View {
    let field: CandidateInputField
    @Binding var value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LearnLabel(field.title, term: field.term)
            TextField(field.title, text: $value)
                .font(.monaspace(.body)).keyboardType(.numbersAndPunctuation)
                .textFieldStyle(.roundedBorder).autocorrectionDisabled()
        }.padding().instrumentCard()
    }
}
