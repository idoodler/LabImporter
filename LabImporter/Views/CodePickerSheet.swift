import SwiftUI

struct CodePickerSheet: View {
    @Binding var code: String
    @Binding var name: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [(code: String, name: String)] {
        guard !query.isEmpty else { return LabMapping.allKnownCodes }
        return LabMapping.allKnownCodes.filter {
            $0.name.localizedCaseInsensitiveContains(query) ||
            $0.code.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            List(filtered, id: \.code) { item in
                Button {
                    code = item.code
                    name = item.name
                    dismiss()
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                            Text(item.code)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .textCase(.uppercase)
                        }
                        Spacer()
                        if code.uppercased() == item.code {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .foregroundStyle(.primary)
            }
            .searchable(text: $query, prompt: Text("Search lab tests"))
            .navigationTitle("Lab Test")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
