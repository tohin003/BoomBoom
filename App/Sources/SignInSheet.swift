import BoomDesign
import BoomPlayer
import SwiftUI
import WebKit

/// Google's own sign-in page, shown in a sheet. It works on one browser profile's data store, the one the hidden
/// tab uses for that account, so signing in here signs that profile in. The app never sees the password: it is typed
/// into Google's page.
struct SignInSheet: View {
    let browser: HiddenBrowser
    let session: SessionStore
    /// The profile being signed in to: the active one, or a new one for another account.
    let profile: String
    @Environment(\.dismiss) private var dismiss
    @State private var isLoading = true
    /// Set when Google sent the listener back; closing the sheet any other way is giving up.
    @State private var didSignIn = false

    var body: some View {
        NavigationStack {
            SignInWebView(dataStore: browser.websiteDataStore(forProfile: profile), isLoading: $isLoading) {
                didSignIn = true
                session.finishAddingAccount(profile)
                dismiss()
            }
            .ignoresSafeArea(edges: .bottom)
            .overlay(alignment: .top) {
                if isLoading { ProgressView().tint(BoomColor.accent).padding(.top, 24) }
            }
            .navigationTitle("Sign in with Google")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
        .onDisappear {
            // Cancel, a swipe down, or another sheet taking its place: a new profile left empty is dropped.
            if !didSignIn { session.cancelAddingAccount(profile) }
        }
    }
}

private struct SignInWebView: UIViewRepresentable {
    let dataStore: WKWebsiteDataStore
    @Binding var isLoading: Bool
    let onSignedIn: () -> Void

    /// Google's page for signing in to YouTube Music; it returns to music.youtube.com when done.
    private static let start = URL(string:
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true"
        + "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop"
        + "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F")!
    /// The sign-in page is shown as Safari on iPhone shows it.
    private static let userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/18.5 Mobile/15E148 Safari/604.1"

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = dataStore
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.customUserAgent = Self.userAgent
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        view.load(URLRequest(url: Self.start))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
    }

    /// Lets go of the profile's data store quickly: a profile given up on is deleted once no web view uses it.
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.stopLoading()
        view.navigationDelegate = nil
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: SignInWebView
        private var didFinish = false

        init(_ parent: SignInWebView) {
            self.parent = parent
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            parent.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.isLoading = false
        }

        func webView(
            _ webView: WKWebView, decidePolicyFor action: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            // Back on YouTube Music: Google is done and the cookies are set.
            if action.targetFrame?.isMainFrame ?? true, action.request.url?.host() == "music.youtube.com", !didFinish {
                didFinish = true
                parent.onSignedIn()
                return .cancel
            }
            return .allow
        }
    }
}
