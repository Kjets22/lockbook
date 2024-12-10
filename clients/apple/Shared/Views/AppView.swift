import SwiftUI

struct AppView: View {
    
    @EnvironmentObject var accounts: AccountService
    @EnvironmentObject var files: FileService
    @EnvironmentObject var errors: ErrorService
    
    @ViewBuilder
    var body: some View {
        VStack {
            if accounts.calculated {
                if accounts.account == nil {
                    OnboardingOneView()
                } else {
                    PlatformView()
                        .onOpenURL() { url in
                            if url.scheme == "lb" {
                                if url.host == "sharedFiles" {
                                    handleImportLink(url: url)
                                } else {
                                    handleOpenLink(url: url)
                                }
                            }
                        }
                        .handlesExternalEvents(preferring: ["lb"], allowing: ["lb"])
                }
            } else {
                Label("Loading...", systemImage: "clock.arrow.circlepath")
            }
        }
        .alert("Error", isPresented: Binding<Bool>(
            get: { errors.error != nil },
            set: { if !$0 { errors.error = nil } }
        ), presenting: errors.error) { _ in
            Button("Dismiss", role: .cancel) {}
        } message: { error in
            Text("An error occurred: \(error.msg)")
        }
        .alert(errors.errorWithTitle?.0 ?? "Error", isPresented: Binding<Bool>(
            get: { errors.errorWithTitle != nil },
            set: { if !$0 { errors.errorWithTitle = nil } }
        ), presenting: errors.errorWithTitle) { _ in
            Button("Dismiss", role: .cancel) {}
        } message: { error in
            Text("\(error.1)")
        }
    }
    
    func handleImportLink(url: URL) {
        if let filePathsQuery = url.query,
           let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.lockbook") {
            let filePaths = filePathsQuery.components(separatedBy: ",")
            
            var res: [String] = []
            
            for filePath in filePaths {
                res.append(containerURL.appendingPathComponent(filePath.removingPercentEncoding!).path(percentEncoded: false))
            }
                                                            
            DI.sheets.movingInfo = .Import(res)
        }

    }
    
    func handleOpenLink(url: URL) {
        guard let uuidString = url.host, let id = UUID(uuidString: uuidString) else {
            DI.errors.showErrorWithTitle("Malformed link", "Cannot open file")
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            while !DI.files.hasRootLoaded {
                Thread.sleep(until: .now + 1)
            }

            Thread.sleep(until: .now + 0.1)

            if DI.files.idsAndFiles[id] == nil {
                DI.errors.showErrorWithTitle("File not found", "That file does not exist in your lockbook")
            }

            DispatchQueue.main.async {
                DI.workspace.requestOpenDoc(id)
            }
        }

    }
    
    let updateAlert: Alert = Alert(
        title: Text("Update Required!"),
        message: Text("It seems like you're using an out-date client. Please update to continue."),
        dismissButton: .default(Text("Dismiss"))
    )
}
