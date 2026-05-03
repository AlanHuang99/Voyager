#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: prepare-github-pages-release.sh <tag> [apk-source-dir] [output-dir]}"
apk_source_dir="${2:-app/build/outputs/apk/release}"
output_dir="${3:-public}"

if [ ! -d "$apk_source_dir" ]; then
  echo "APK source directory does not exist: $apk_source_dir" >&2
  exit 1
fi

mapfile -t source_apks < <(find "$apk_source_dir" -maxdepth 1 -type f -name "*.apk" | sort)
if [ "${#source_apks[@]}" -eq 0 ]; then
  echo "No APKs found in $apk_source_dir" >&2
  exit 1
fi

rm -rf "$output_dir"
mkdir -p "$output_dir/downloads"

declare -a release_apks=()
for source_apk in "${source_apks[@]}"; do
  name="$(basename "$source_apk")"
  variant="${name%.apk}"
  variant="${variant#app-}"
  variant="${variant%-release-unsigned}"
  variant="${variant%-release}"
  if [ -z "$variant" ] || [ "$variant" = "release" ]; then
    variant="universal"
  fi

  destination="$output_dir/downloads/voyager-${tag}-${variant}.apk"
  cp "$source_apk" "$destination"
  release_apks+=("$destination")
done

(
  cd "$output_dir/downloads"
  sha256sum *.apk > SHA256SUMS.txt
)

recommended_apk="${release_apks[0]}"
for apk in "${release_apks[@]}"; do
  if [[ "$(basename "$apk")" == *"-universal.apk" ]]; then
    recommended_apk="$apk"
    break
  fi
done

released_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
version_name="${tag#v}"
version_code=""
if [ -f app/build.gradle.kts ]; then
  version_code="$(sed -nE 's/[[:space:]]*versionCode = ([0-9]+)/\1/p' app/build.gradle.kts | head -n 1)"
fi

manifest="$output_dir/latest.json"
{
  echo "{"
  echo "  \"app\": \"Voyager\","
  echo "  \"tag\": \"$tag\","
  echo "  \"versionName\": \"$version_name\","
  echo "  \"versionCode\": \"$version_code\","
  echo "  \"releasedAt\": \"$released_at\","
  echo "  \"recommended\": \"downloads/$(basename "$recommended_apk")\","
  echo "  \"downloads\": ["
  for index in "${!release_apks[@]}"; do
    apk="${release_apks[$index]}"
    comma=","
    if [ "$index" -eq "$((${#release_apks[@]} - 1))" ]; then
      comma=""
    fi
    file_name="$(basename "$apk")"
    label="${file_name%.apk}"
    label="${label#voyager-${tag}-}"
    sha256="$(sha256sum "$apk" | awk '{print $1}')"
    bytes="$(stat -c "%s" "$apk")"
    echo "    {\"variant\": \"$label\", \"file\": \"downloads/$file_name\", \"sha256\": \"$sha256\", \"bytes\": $bytes}$comma"
  done
  echo "  ]"
  echo "}"
} > "$manifest"

cat > "$output_dir/index.html" <<HTML
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Voyager Downloads</title>
  <style>
    :root {
      color-scheme: light dark;
      --bg: #f8fafc;
      --surface: #ffffff;
      --text: #111827;
      --muted: #4b5563;
      --border: #d1d5db;
      --accent: #2563eb;
      --accent-text: #ffffff;
    }

    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #0f172a;
        --surface: #111827;
        --text: #f8fafc;
        --muted: #cbd5e1;
        --border: #334155;
        --accent: #60a5fa;
        --accent-text: #082f49;
      }
    }

    * {
      box-sizing: border-box;
    }

    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      line-height: 1.5;
    }

    main {
      width: min(920px, calc(100% - 32px));
      margin: 0 auto;
      padding: 40px 0;
    }

    header {
      margin-bottom: 28px;
    }

    h1 {
      margin: 0 0 8px;
      font-size: clamp(2rem, 4vw, 3rem);
      line-height: 1.1;
    }

    p {
      margin: 0;
      color: var(--muted);
    }

    .release {
      display: grid;
      gap: 16px;
      margin-top: 24px;
    }

    .download {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 18px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
    }

    .download strong {
      display: block;
      margin-bottom: 4px;
    }

    .button {
      display: inline-flex;
      align-items: center;
      min-height: 44px;
      padding: 0 16px;
      border-radius: 6px;
      background: var(--accent);
      color: var(--accent-text);
      font-weight: 700;
      text-decoration: none;
    }

    code {
      overflow-wrap: anywhere;
      color: var(--muted);
    }
  </style>
</head>
<body>
  <main>
    <header>
      <h1>Voyager $tag</h1>
      <p>Signed Android APK downloads published from GitHub Actions. F-Droid builds are produced and signed independently from the F-Droid metadata.</p>
    </header>

    <section class="release" aria-label="Release downloads">
      <article class="download">
        <div>
          <strong>Recommended APK</strong>
          <code>$(basename "$recommended_apk")</code>
        </div>
        <a class="button" href="downloads/$(basename "$recommended_apk")">Download</a>
      </article>

      <article class="download">
        <div>
          <strong>Checksums</strong>
          <code>SHA256SUMS.txt</code>
        </div>
        <a class="button" href="downloads/SHA256SUMS.txt">View</a>
      </article>

      <article class="download">
        <div>
          <strong>Machine-readable release metadata</strong>
          <code>latest.json</code>
        </div>
        <a class="button" href="latest.json">View</a>
      </article>
    </section>
  </main>
</body>
</html>
HTML

echo "Prepared $output_dir for $tag with ${#release_apks[@]} APK(s)."
