cask "trawl" do
  arch arm: "arm64"

  version "1.1.0"
  sha256 "2ebef7a5002b5e94bea1e95db74ba5942779ccf911d0921d8b6581a9d1ce0dd4"

  url "https://github.com/Crane-AI-Labs/trawl/releases/download/desktop-#{version}/Trawl-#{version}-#{arch}.dmg",
      verified: "github.com/Crane-AI-Labs/trawl/"
  name "Trawl"
  desc "Offline semantic search for your local files — nothing leaves your computer"
  homepage "https://trawlsearch.com/"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on arch: :arm64
  depends_on macos: ">= :ventura"

  app "Trawl.app"

  zap trash: [
    "~/Library/Application Support/Trawl",
    "~/Library/LaunchAgents/ai.craneailabs.trawl.autostart.plist",
    "~/Library/Preferences/ai.craneailabs.trawl.plist",
  ]
end
