# TripLens - Product Requirements Document (PRD)

> **Version**: 1.0 (MVP)
> **Author**: Chris Jia
> **Date**: 2026-03-22
> **Status**: Draft

---

## 1. Product Overview

### 1.1 One-liner

TripLens is a lightweight travel recording tool that automatically captures GPS trajectories and indexes multimedia from all your devices onto a unified timeline. Your data stays entirely on your device.

### 1.2 Problem Statement

Travelers face a fragmented memory problem:

- Phone photos have GPS data, but camera photos do not. Over time, it becomes impossible to recall where camera photos were taken without cross-referencing phone photos from the same time.
- GPS trajectories, photos, videos, voice memos, and text notes are scattered across different apps and devices with no way to unify them.
- Existing travel journal apps (Polarsteps, FindPenguins, etc.) lock user data inside their ecosystems, offer no multi-device media support, and lack voice memo functionality.

### 1.3 Solution

TripLens consists of two components:

1. **Mobile App (手机端)**: A lightweight sensor that runs in the background during travel, recording GPS trajectories, auto-indexing phone gallery media, and accepting user voice/text notes. It produces a structured **Trip Index File (旅行索引文件)** that references but never duplicates the user's media files.
2. **Desktop Tool (电脑端)**: A simple application that imports the Trip Index File, matches external camera photos/videos by timestamp against the recorded trajectory, and writes inferred GPS coordinates back into the files' EXIF/metadata.

### 1.4 Core Principles

- **Data Ownership (数据归属)**: All data stays on the user's device. No cloud upload. No account required.
- **Non-duplicating Index (非复制索引)**: The app creates an index that references media files, never copies them.
- **Open Format (开放格式)**: The Trip Index File is a documented, portable format. Users can export, back up, and use it with other tools.
- **Minimal Friction (最低使用摩擦)**: One tap to start recording. Everything else is automatic.

### 1.5 Target Users (MVP)

- Travelers who carry both a phone and a dedicated camera (相机 + 手机双持用户)
- Photography enthusiasts who want GPS data on their camera photos
- Anyone who wants a unified, structured record of their travels without being locked into a platform

### 1.6 Competitive Landscape (竞品分析)

| Feature | Polarsteps | FindPenguins | Pebbls | Day One | **TripLens** |
|---|---|---|---|---|---|
| Auto GPS Tracking (自动轨迹记录) | ✅ | ✅ | ✅ | ❌ | ✅ |
| Transport Mode Label (交通方式标注) | ❌ | Flights only | ❌ | ❌ | ✅ (speed-based) |
| Phone Photo Auto-link (手机照片自动关联) | ✅ | ✅ | Manual | Manual | ✅ |
| External Camera Support (外部相机支持) | ❌ | ❌ | ❌ | ❌ | ✅ |
| EXIF GPS Write-back (GPS写回EXIF) | ❌ | ❌ | ❌ | ❌ | ✅ |
| Voice Notes (语音笔记) | ❌ | ❌ | ❌ | ✅ | ✅ |
| Text Notes (文字笔记) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Data Export (数据导出) | Limited | Limited | ❌ | ✅ | ✅ (open format) |
| No Cloud / No Account (无云端/无账户) | ❌ | ❌ | ❌ | ❌ | ✅ |
| In-app Social (应用内社交) | ✅ | ✅ | ✅ | ❌ | ❌ (by design) |

**Key differentiators (核心差异化)**:
- Only product that supports external camera photo geo-tagging via trajectory matching
- Only product with fully open, portable data format and no cloud dependency
- Combination of auto-tracking + multi-device media unification + voice/text notes in a single lightweight tool

---

## 2. Information Architecture (信息架构)

### 2.1 Data Model

```
TripGroup (旅行分组) {
  id: string (UUID)
  name: string                    // User-editable, default: "{date} - {city}"
  created_at: timestamp
  updated_at: timestamp
  sessions: Session[]
}

Session (记录会话) {
  id: string (UUID)
  group_id: string                // Reference to parent TripGroup
  name: string                    // User-editable, default: "Day {n}" or date
  start_time: timestamp
  end_time: timestamp | null      // null while recording is active
  status: "recording" | "completed" | "interrupted"
  track: TrackPoint[]
  media: MediaReference[]
  notes: Note[]
}

TrackPoint (轨迹点) {
  timestamp: timestamp
  latitude: number
  longitude: number
  altitude: number | null
  accuracy: number                // GPS accuracy in meters
  speed: number | null            // meters per second
  transport_mode: "stationary" | "walking" | "cycling" | "driving" | "fast_transit"
                                  // Inferred from speed thresholds
}

MediaReference (媒体引用) {
  id: string (UUID)
  type: "photo" | "video"
  source: "phone_gallery" | "external_camera"
  
  // For phone gallery media
  content_uri: string | null      // Android content:// URI
  
  // For external camera media (desktop tool fills these)
  original_filename: string | null
  
  // Time and location
  captured_at: timestamp          // EXIF capture time
  timestamp_offset: number        // Offset in seconds to correct camera clock drift (default 0)
  
  // Location
  original_location: {lat, lng} | null      // From EXIF (phone photos have this; camera photos usually don't)
  inferred_location: {lat, lng} | null      // Interpolated from trajectory
  location_source: "exif" | "trajectory" | null
  
  // Matching metadata
  matched_session_id: string | null
  matched_trackpoint_index: number | null   // Nearest trackpoint for reference
}

Note (笔记) {
  id: string (UUID)
  type: "text" | "voice"
  
  // For text notes
  content: string | null
  
  // For voice notes
  audio_filename: string | null   // Relative path within app storage, e.g., "notes/note_001.m4a"
  duration_seconds: number | null
  
  // Auto-captured context
  created_at: timestamp
  location: {lat, lng}
  matched_session_id: string
}
```

### 2.2 Transport Mode Classification (交通方式分类)

MVP uses simple speed-based thresholds applied to each TrackPoint:

| Speed Range | Classified As | Icon |
|---|---|---|
| < 1 km/h | `stationary` (静止) | ⏸ |
| 1 - 6 km/h | `walking` (步行) | 🚶 |
| 6 - 20 km/h | `cycling` (骑行) | 🚲 |
| 20 - 120 km/h | `driving` (驾车) | 🚗 |
| > 120 km/h | `fast_transit` (高速交通: 高铁/飞机) | 🚄 |

Classification is applied per-point. When displaying a track segment (轨迹段), consecutive points with the same mode are grouped. Very short segments (e.g. a single `cycling` point between two `driving` segments) should be smoothed to the surrounding mode to avoid noise.

### 2.3 Trip Index File Format (旅行索引文件格式)

> **TODO**: Finalize the exact export file format during technical design phase. The following is the intended structure for reference. The format should be a `.zip` archive with the following layout:

```
triplens-{group_name}-{date}/
├── index.json              // Serialized TripGroup with all Sessions, TrackPoints, MediaReferences, Notes
├── tracks/
│   └── session_{id}.gpx    // Standard GPX file per session (for compatibility with other tools)
├── notes/
│   ├── note_001.m4a        // Voice note audio files
│   ├── note_002.m4a
│   └── ...
└── README.txt              // Brief explanation of the file format for end users
```

Key design decisions:
- **Photos and videos are NOT included** in the export file. They are too large and already exist on the user's device. `index.json` contains only references (filename, timestamp, dimensions) for matching purposes.
- **Voice notes ARE included** because they are created by the app and stored in app-private storage, which may not be accessible elsewhere.
- **GPX files are included** for interoperability with other GPS tools and mapping software.

---

## 3. Mobile App - Feature Specification (手机端功能规格)

### 3.1 Platform & Language

- **Platform**: Android (MVP). iOS support planned for future versions.
- **Languages**: Simplified Chinese (简体中文), English. Language follows system setting with manual override in app settings.

### 3.2 App Structure (应用结构)

The app has 3 main screens, accessible via bottom navigation or contextual flow:

```
[Home / Recording Screen] ---- [Trip List Screen] ---- [Settings Screen]
      (首页/记录页面)             (旅行列表页面)           (设置页面)
```

When no recording is active, the app opens to **Trip List Screen** as the default.
When a recording is active, the app opens to **Home / Recording Screen** as the default.

### 3.3 Screen: Home / Recording Screen (首页/记录页面)

#### 3.3.1 State: No Active Recording (未在记录状态)

**Layout**:
- Center of screen: A prominent "Start Recording" (开始记录) button (large, circular, colored)
- Below the button: Brief text prompt - "Tap to start your journey" / "点击开始你的旅程"
- Top-right: Gear icon to navigate to Settings

**Behavior**:
- Tapping "Start Recording" triggers:
  1. Check location permission. If not granted, show a guided permission request dialog explaining why "Always Allow" (始终允许) is needed. If user declines, show a message explaining the app cannot function without it, with a button to open system settings.
  2. If permission is granted, prompt the user to select a TripGroup:
     - Show a dropdown/dialog with existing TripGroups + an option to "Create New Trip" (创建新旅行)
     - If "Create New Trip" is selected, create a new TripGroup with a default name (format: "YYYY-MM-DD {City}", where city is resolved via reverse geocoding of current location; if offline, use "YYYY-MM-DD" only and fill city later)
  3. Create a new Session within the selected TripGroup, set status to `recording`, and transition to the active recording state.

#### 3.3.2 State: Active Recording (记录中状态)

**Layout (top to bottom)**:

**Top bar (顶部栏)**:
- Left: Current TripGroup name (tappable to rename)
- Right: Recording duration timer (e.g. "02:34:15") with a pulsing red dot indicator
- Right edge: "Stop" (结束) button

**Map area (地图区域, ~60% of screen height)**:
- Full-width interactive map (MapLibre GL Native + OpenFreeMap tiles)
- The recorded trajectory is drawn as a colored polyline on the map
  - Color-coded by transport mode:
    - `stationary`: gray
    - `walking`: green
    - `cycling`: orange
    - `driving`: blue
    - `fast_transit`: purple
- Media points (媒体点) are shown as small circular thumbnails (for photos) or icons (for video/voice/text) along the trajectory at their respective locations
- The map auto-follows the user's current position (with a button to re-center if user has manually panned)
- Current position shown with a standard blue dot with accuracy circle

**Bottom panel (底部面板, ~40% of screen height)**:
- Two action buttons side by side:
  - "Text Note" (文字笔记) button with a text/pencil icon
  - "Voice Note" (语音笔记) button with a microphone icon
- Below the buttons: A compact scrollable horizontal strip showing the most recent media items (latest 10 photos/notes) as thumbnails, sorted by time descending. Tapping a thumbnail opens the media preview (see Section 3.5).

**Behavior**:

- **GPS Tracking**: Runs as an Android Foreground Service (前台服务) with a persistent notification showing "TripLens is recording your journey" / "TripLens 正在记录你的旅程". TrackPoints are recorded at a dynamic interval:
  - When moving (speed > 1 km/h): record every 5-10 seconds
  - When stationary (speed < 1 km/h): record every 60 seconds
  - This balances accuracy and battery consumption

- **Gallery Scanning (相册扫描)**: Every 60 seconds, the app queries Android's `MediaStore` for photos and videos with a `DATE_TAKEN` timestamp after the session's `start_time`. Newly detected media are added to the session's `media` array as `MediaReference` entries with `source: "phone_gallery"`. Their `original_location` is read from EXIF if available.

- **Text Note**: Tapping opens a bottom sheet (底部弹窗) with a text input field and a "Save" (保存) button. On save, a Note is created with `type: "text"`, the current timestamp, and the current GPS location.

- **Voice Note**: Tapping starts audio recording immediately. The button transforms into a recording state (pulsing red, shows elapsed time). Tapping again stops recording and saves. The audio file is stored in app-private storage (e.g., `{app_data}/notes/note_{uuid}.m4a`). A Note is created with `type: "voice"`.

- **Stop Recording**: Tapping "Stop" shows a confirmation dialog: "End this session?" / "结束本次记录？" with options "End" (结束) and "Cancel" (取消). On confirmation, the session's `end_time` is set, `status` changes to `completed`, the foreground service is stopped, and the user is navigated to the Trip List Screen.

- **App killed by system (应用被系统杀死)**: If the foreground service is killed by the system (memory pressure, battery optimization), on next app open:
  - If a session was in `recording` status, show a dialog: "Your last recording was interrupted. Resume recording?" / "上次记录被中断，是否继续记录？"
  - "Resume" (继续): Create a new Session in the same TripGroup, start recording. The interrupted session's status is set to `interrupted`.
  - "Discard" (放弃): Set the interrupted session's status to `interrupted` and go to Trip List.

### 3.4 Screen: Trip List (旅行列表页面)

**Layout**:

Modeled after Google Fit's activity history, with TripLens-specific additions.

**Top section**: Page title "My Trips" / "我的旅行"

**List of TripGroups (旅行分组列表)**, sorted by most recent first. Each TripGroup card shows:
- TripGroup name (tappable to rename inline)
- Date range (e.g. "Mar 15 - Mar 20, 2026")
- Number of sessions, total duration, total distance
- A small trajectory thumbnail (轨迹缩略图): a simplified polyline rendered on a mini static map
- Number of photos, videos, and notes (with small icons)

**Tapping a TripGroup** expands it or navigates to a **TripGroup Detail Screen (旅行详情页面)**, which shows:
- Header: TripGroup name, date range, summary stats (total distance, duration, photo count, note count)
- A list of Sessions within the group, each showing:
  - Session name (default "Day 1", "Day 2" or date, user-editable)
  - Date + time range
  - Duration + distance
  - Transport mode breakdown (e.g., "🚶 2.3km  🚗 45km  🚄 320km")
- Tapping a Session enters the **Session Review Screen (会话回顾页面)** (see Section 3.5)
- A floating action button (FAB) with "Export" (导出) icon

**Actions on TripGroup**:
- Long press or swipe: "Rename" (重命名), "Delete" (删除, with confirmation dialog), "Export" (导出)
- "Export" generates the Trip Index File (see Section 2.3) and triggers Android's share sheet, allowing the user to save it to a file manager, send via messaging apps, etc.

### 3.5 Screen: Session Review (会话回顾页面)

**Layout (top to bottom)**:

**Map area (~50% of screen height)**:
- Full trajectory for this session, color-coded by transport mode
- Media and note markers along the trajectory (same icons as Recording Screen)
- Tapping a marker on the map opens the corresponding item in the bottom sheet timeline

**Timeline area (~50% of screen height, scrollable)**:
- A vertical timeline (时间轴) showing all items in chronological order:
  - **Track segments**: Shown as compact cards with transport mode icon, distance, and duration (e.g., "🚶 Walking - 1.2km - 23min")
  - **Photos**: Shown as thumbnail images with timestamp and location name (reverse geocoded)
  - **Videos**: Shown as thumbnail with a play icon overlay, timestamp, and duration
  - **Text notes**: Shown as text cards with timestamp
  - **Voice notes**: Shown as audio player cards with timestamp and duration, with a play button
- Tapping a photo/video opens it in a full-screen viewer (system default or in-app viewer)
- Tapping a timeline item also highlights its location on the map above and pans the map to center on it

**Bottom sheet media preview (底部卡片媒体预览)**:
- When a map marker is tapped, a bottom sheet slides up showing all media items at that location/time cluster
- Items are shown as a horizontal scrollable gallery
- Swipe up to expand to full screen, swipe down to dismiss

### 3.6 Screen: Settings (设置页面)

**Settings items**:

- **Language (语言)**: "Follow System" (跟随系统) / "简体中文" / "English"
- **Recording Accuracy (记录精度)**:
  - "Standard" (标准, default): GPS fix every 5-10 seconds when moving, every 60 seconds when stationary. Estimated battery consumption: ~3-5% per hour.
  - "High" (高精度): GPS fix every 3-5 seconds always. Estimated battery consumption: ~6-10% per hour. Recommended for hiking.
  - "Battery Saver" (省电): GPS fix every 30-60 seconds. Estimated battery consumption: ~1-2% per hour. Less accurate trajectory.
- **Gallery Scan Interval (相册扫描间隔)**: 30s / 60s (default) / 120s
- **Storage Usage (存储用量)**: Show total storage used by voice notes and trip data, with a "Clear All Data" (清除所有数据) option (with confirmation)
- **About (关于)**: App version, open source licenses, link to project page
- **Privacy Policy (隐私政策)**: See Section 7

---

## 4. Desktop Tool - Feature Specification (电脑端功能规格)

### 4.1 Platform & Technology

- **Platform**: Windows (MVP). macOS support planned for future versions.
- **Form Factor**: Electron-based desktop application with a simple GUI.
- **Languages**: Simplified Chinese (简体中文), English. Language follows system setting with manual override.

### 4.2 Application Flow

The desktop tool is a single-purpose utility focused on one workflow: **import a Trip Index File + match external camera photos by timestamp + preview matches + write GPS back to EXIF**.

#### Step 1: Import Trip Index File (导入旅行索引文件)

**Layout**: A clean landing screen with:
- A large drop zone: "Drop your TripLens export file here, or click to browse" / "将 TripLens 导出文件拖到这里，或点击浏览"
- Accepts the `.zip` file exported from the mobile app
- On successful import, display a summary: TripGroup name, date range, number of sessions, total trajectory points, trajectory preview on a small map

#### Step 2: Select Photo Folder (选择照片文件夹)

**Layout**: 
- A folder picker button: "Select camera photos folder" / "选择相机照片文件夹"
- After selection, the tool scans the folder (and subfolders) for image files (`.jpg`, `.jpeg` in MVP)
- Display: total files found, number of files that fall within the trip's time range, number of files that already have GPS data (these will be skipped)

#### Step 3: Time Offset Calibration (时间偏移校准)

**Purpose**: Camera clocks often drift from phone clocks. This step lets the user correct the offset.

**Layout**:
- Display: "Your camera's clock may differ from your phone's. Adjust the offset below." / "你的相机时钟可能与手机不完全一致，请在下方调整偏移量。"
- A slider and numeric input for offset in seconds (range: -3600 to +3600, default: 0)
  - Label: "Camera is {X} seconds ahead/behind phone" / "相机比手机快/慢 {X} 秒"
- A helper tip: "Tip: Find a photo you took with both your camera and phone at the same time. Compare the timestamps to determine the offset." / "提示：找一张同时用手机和相机拍摄的照片，对比时间戳即可确定偏移量。"
- As the user adjusts the offset, the preview in Step 4 updates in real time

#### Step 4: Match Preview (匹配预览)

**Layout**:
- A table/list showing each camera photo with:
  - Thumbnail (缩略图)
  - Original filename (原始文件名)
  - Camera capture time (相机拍摄时间, with offset applied)
  - Matched location (匹配位置, as "lat, lng" and reverse-geocoded place name if possible)
  - Time gap (时间差): Difference between the photo's timestamp and the nearest TrackPoint. Displayed in seconds.
  - Confidence indicator (置信度):
    - 🟢 Green: time gap < 30 seconds (high confidence)
    - 🟡 Yellow: time gap 30-300 seconds (medium confidence)
    - 🔴 Red: time gap > 300 seconds (low confidence, location may be inaccurate)
    - ⚫ Gray: no matching trajectory data for this time (outside any session's recording window)
  - A checkbox (default checked for 🟢 and 🟡, unchecked for 🔴 and ⚫) for the user to include/exclude individual files
- A map panel on the side or top showing all matched locations as markers. Hovering/clicking a table row highlights the corresponding marker.
- Summary bar: "X of Y photos matched. Z will be geo-tagged." / "Y 张照片中 X 张已匹配，Z 张将被写入GPS。"

#### Step 5: Confirm & Write (确认并写入)

**Layout**:
- A prominent "Write GPS to Photos" (写入GPS到照片) button
- A checkbox: "Create backup of original files before writing" (写入前备份原始文件, default: checked)
  - If checked, before modifying each file, copy the original to a `_triplens_backup/` subfolder in the same directory
- On clicking the button, show a confirmation dialog: "This will modify the EXIF data of {N} photos. This operation modifies the original files. A backup will be created in `_triplens_backup/`. Proceed?" / "即将修改 {N} 张照片的EXIF数据。此操作将修改原始文件，备份将保存在 `_triplens_backup/` 文件夹中。是否继续？"
- On confirmation, process each file:
  - Show a progress bar with current file name
  - Write `GPSLatitude`, `GPSLongitude`, `GPSLatitudeRef`, `GPSLongitudeRef` to EXIF
  - Do NOT overwrite existing GPS data (skip files that already have GPS coordinates)
- On completion: "Done! {N} photos have been geo-tagged." / "完成！已为 {N} 张照片写入GPS信息。" with an "Open Folder" (打开文件夹) button

### 4.3 Supported File Formats (支持的文件格式)

**MVP**:
- Input photos: `.jpg`, `.jpeg` (EXIF write-back supported)

**Future versions (后续版本)**:
- RAW formats: `.cr3` (Canon), `.arw` (Sony), `.nef` (Nikon), `.raf` (Fujifilm), `.dng`
- Video files: `.mp4`, `.mov` (metadata write-back)

---

## 5. Privacy & Permissions (隐私与权限)

### 5.1 Permissions Required (所需权限)

**Android permissions**:

| Permission | Purpose | When Requested |
|---|---|---|
| `ACCESS_FINE_LOCATION` | GPS trajectory recording | When user taps "Start Recording" for the first time |
| `ACCESS_BACKGROUND_LOCATION` | Continue recording when app is in background | After fine location is granted, with explanation dialog |
| `FOREGROUND_SERVICE_LOCATION` | Run location tracking as foreground service | Automatically with foreground service |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Scan gallery for photos/videos taken during trip | When user taps "Start Recording" for the first time |
| `RECORD_AUDIO` | Voice notes | When user taps "Voice Note" for the first time |
| `POST_NOTIFICATIONS` | Show persistent notification during recording | On first recording start (Android 13+) |

### 5.2 Privacy Policy Summary (隐私政策要点)

The full privacy policy must be accessible from the Settings screen and from app store listing. Key points:

- **No data leaves your device.** TripLens does not upload any data to any server. There is no cloud service, no analytics, no telemetry.
- **No account required.** TripLens does not collect email addresses, names, or any personal identifiers.
- **Location data is only collected while recording is active.** When the user is not actively recording a trip, TripLens does not access location services at all.
- **Gallery access is read-only.** TripLens reads photo/video metadata (timestamp, GPS coordinates, filename) from the device gallery. It does not modify, copy, or upload any media files.
- **Voice notes are stored locally.** Audio recordings are saved in the app's private storage directory and are only accessible through the app or via the export function.
- **The export file contains GPS trajectory data and note content.** Users should be aware that sharing the export file with others will share their location history for the recorded period.

### 5.3 Permission Request Guidance (权限请求引导)

When requesting sensitive permissions, the app must show a clear explanation before the system dialog:

**Location permission (定位权限)**:
- Title: "TripLens needs location access to record your journey" / "TripLens 需要定位权限来记录你的旅程"
- Body: "Your location data is stored only on this device and never uploaded anywhere. TripLens only accesses your location while you are actively recording a trip." / "你的位置数据仅存储在本设备上，不会上传到任何地方。TripLens 仅在你主动记录旅行时访问你的位置。"

**Background location (后台定位)**:
- Title: "Allow background location for continuous tracking" / "允许后台定位以持续记录"
- Body: "To keep recording when you switch to other apps or lock your phone, TripLens needs 'Allow all the time' location access. Without this, recording will stop when you leave the app." / "为了在你切换应用或锁屏时继续记录，TripLens 需要'始终允许'的定位权限。否则，离开应用后记录将中断。"

---

## 6. Business Model (商业模式)

### 6.1 Monetization Strategy

**Freemium with one-time paid upgrade (免费增值 + 一次性付费)**

This model aligns with the "data belongs to the user" principle. No subscriptions, no recurring fees.

**Free tier (免费版)**:
- Full GPS trajectory recording
- Auto-indexing of phone gallery media
- Text and voice notes
- Basic session review (map + timeline)
- Export Trip Index File

**Paid tier - one-time purchase (付费版 - 一次性购买, suggested price $5-10 USD)**:
- External camera photo geo-tagging (desktop tool)
- EXIF GPS write-back
- Advanced display templates for session review
- Multiple export formats

### 6.2 Future Revenue Opportunities (未来收入机会)

- **Premium export templates**: Beautifully designed share card templates, travel report themes (small one-time purchases)
- **AI story generation**: Use on-device or user-provided API key to auto-generate travel narratives from trajectory + photos + notes (one-time feature unlock)
- **Physical travel book**: Partner with print-on-demand services to produce photo books from trip data (per-order commission)

---

## 7. Future Roadmap (后续功能规划)

The following features are planned for future versions. They are listed here to ensure they are not forgotten and to inform architectural decisions in MVP development.

### 7.1 Mobile App - Future Features

| Priority | Feature | Description |
|---|---|---|
| High | **iOS version (iOS版本)** | Port the mobile app to iOS. Architectural decisions in MVP should consider cross-platform feasibility. |
| High | **Smart transport mode detection (智能交通方式识别)** | Replace speed-threshold classification with accelerometer + sensor-based ML model. Distinguish between car, bus, train, tram/metro. Use on-device inference (e.g., TFLite). |
| High | **Offline map support (离线地图)** | Allow users to download map tiles for offline use during travel in areas without connectivity. |
| High | **Cross-device file sync via user's cloud drive (通过用户云盘跨设备同步)** | Store Trip Index Files in user's Google Drive / OneDrive / iCloud Drive folder. Mobile writes, desktop reads. No proprietary cloud needed. |
| Medium | **Camera WiFi import (相机WiFi导入)** | During travel, import photos from camera via WiFi transfer (supported by most modern cameras). Auto-match and tag in real-time. |
| Medium | **Voice note transcription (语音笔记转文字)** | On-device speech-to-text for voice notes using Whisper or similar model. Store transcript alongside audio. |
| Medium | **Trip merging and splitting (旅行合并与拆分)** | Allow users to merge multiple TripGroups or split a TripGroup into separate ones. Move sessions between groups. |
| Medium | **Widget / Quick tile (桌面小组件/快捷磁贴)** | Android home screen widget and notification quick tile for one-tap start/stop recording without opening the app. |
| Medium | **Apple Watch / WearOS companion (手表端)** | Quick voice note recording from wrist. View current recording stats. |
| Low | **Collaborative trips (多人协作旅行)** | Multiple users record simultaneously; merge their sessions into one TripGroup via local file exchange (no server). |
| Low | **Auto-generated trip summary (自动生成旅行摘要)** | AI-powered summary of the trip based on trajectory, photos, and notes. Runs locally or with user-provided LLM API key. |

### 7.2 Desktop Tool - Future Features

| Priority | Feature | Description |
|---|---|---|
| High | **macOS version (macOS版本)** | Port the desktop tool to macOS. |
| High | **RAW file support (RAW文件支持)** | Support EXIF write-back for common RAW formats: .cr3, .arw, .nef, .raf, .dng. |
| High | **Video file support (视频文件支持)** | Support metadata write-back for .mp4, .mov files. |
| Medium | **NAS / external drive browsing (NAS/移动硬盘浏览)** | Browse and select photos from network drives and external storage. |
| Medium | **Batch processing (批量处理)** | Process multiple Trip Index Files at once against a large photo library. |
| Medium | **Auto time offset detection (自动时间偏移检测)** | Analyze phone photos and camera photos taken at similar times/locations to automatically suggest the time offset. |
| Low | **Cloud drive integration (云盘集成)** | Read photos directly from Google Drive / OneDrive / NAS without downloading first. |

### 7.3 Export & Sharing - Future Features

| Priority | Feature | Description |
|---|---|---|
| High | **Share card generation (分享卡片生成)** | Generate a visually appealing image (长图) with route map, key photos, and stats. Suitable for posting on Instagram / Xiaohongshu (小红书) / WeChat Moments (朋友圈). |
| High | **Static HTML export (静态HTML导出)** | Generate a self-contained HTML file with embedded map, photos, and timeline. Can be opened in any browser, hosted on any web server, or sent as a file. |
| Medium | **GPX-only export (仅GPX导出)** | Export just the trajectory as a standard GPX file for use with other mapping tools. |
| Medium | **Video slideshow generation (视频幻灯片生成)** | Generate a short video showing the trip route animated on a map with photo highlights. |
| Low | **Integration with blogging platforms (博客平台集成)** | Export trip data formatted for WordPress, Notion, or other blogging tools. |

---

## 8. Design Guidelines (设计指南)

### 8.1 Visual Style

- **Minimal and functional (极简功能导向)**. The app should feel like a well-made tool, not a social media platform.
- **Map-centric (以地图为中心)**. The map is the primary visual element. UI elements should not compete with it.
- **Muted color palette with transport mode accents**. Background and chrome should be neutral (white/light gray in light mode, dark gray in dark mode). Transport mode colors (green, orange, blue, purple) provide visual interest without being overwhelming.
- **Support both light and dark mode (支持明暗主题)**, following system setting.

### 8.2 Interaction Principles

- **One-tap to start (一键开始)**. The most common action (start recording) should require the minimum number of taps.
- **No interruptions during travel (旅途中不打扰)**. Once recording starts, the app should not show popups, tips, or promotions. The user should feel confident that it's working silently in the background.
- **Confirmation for destructive actions (破坏性操作需确认)**. Deleting a trip, stopping a recording, and EXIF write-back all require explicit confirmation.

### 8.3 Naming Conventions

- App name: **TripLens**
- A single recording session: **Session (会话/记录)**
- A group of sessions forming one trip: **Trip (旅行/旅行分组)** (internally `TripGroup`)
- A point on the map with media: **Moment (时刻)** (user-facing term for a media cluster at a location)
- The export file: **Trip Archive (旅行档案)**

---

## 9. Success Metrics (成功指标)

For MVP, since there is no analytics or telemetry, success is measured qualitatively:

- The developer (Chris) can complete a full workflow: record a trip → review on phone → export → import on desktop → geo-tag camera photos → verify GPS in photo viewer
- Battery consumption during a full-day recording session stays under 15%
- The export + import + geo-tag workflow takes less than 5 minutes for a trip with 500 camera photos
- A non-technical user can understand and complete the desktop geo-tagging workflow without external instructions

---

## 10. Open Questions & Decisions Log (待定事项与决策记录)

| ID | Question | Status | Decision |
|---|---|---|---|
| Q1 | Exact export file format (index.json schema, GPX options) | TODO | To be finalized during technical design |
| Q2 | Cross-platform framework choice for mobile app (native Android vs Flutter vs React Native vs Kotlin Multiplatform) | TODO | Must consider future iOS port. To be decided in technical design. |
| Q3 | Map SDK selection (Google Maps vs Mapbox vs OpenStreetMap-based) | **Decided** | MapLibre GL Native + OpenFreeMap tiles. Fully free, no API key or billing required. OSM data quality is sufficient for trajectory display. See TDD Section 1.2. |
| Q4 | Electron vs Tauri for desktop tool | TODO | Tauri is lighter-weight but less mature. To be evaluated. |
| Q5 | Voice note audio format and compression | TODO | M4A (AAC) is preferred for quality/size balance |
| Q6 | Maximum session duration / auto-pause behavior | TODO | What happens if user forgets to stop recording overnight? Consider auto-pause after X hours of `stationary` mode. |
| Q7 | Photo matching: What to do about timezone issues when camera clock is in a different timezone than the trip | TODO | May need timezone-aware timestamp handling in calibration step |

---

## Appendix A: User Scenarios (用户场景)

### Scenario 1: Weekend City Trip (周末城市旅行)

Chris and Yuna visit Wellington for a weekend. On Saturday morning, Chris opens TripLens, creates a new Trip called "Wellington Weekend", and taps "Start Recording". He puts his phone in his pocket and they start exploring.

Throughout the day, Chris takes photos with his Sony camera and his phone. Yuna takes photos on her phone too (her photos won't be indexed - only Chris's phone gallery is scanned). Chris occasionally opens the app to add a quick text note about a great cafe they found.

In the evening, Chris opens TripLens, sees the day's trajectory with all his phone photos mapped along it, and taps "Stop". On Sunday, he starts a new session in the same "Wellington Weekend" trip.

Back home, Chris exports the Trip Archive from his phone, transfers it to his PC via USB. He opens the TripLens desktop tool, imports the archive, points it to the folder where he imported his Sony photos, adjusts the time offset by -45 seconds (his camera was slightly behind), reviews the matches, and clicks "Write GPS". Now all his Sony photos have GPS coordinates embedded.

### Scenario 2: Multi-week International Trip (多周国际旅行)

Chris travels to Japan for three weeks. He creates a Trip called "Japan 2026" and starts a new session each day. Some days he forgets to stop recording before sleeping - the app continues recording but the stationary overnight hours are clearly visible and he can simply ignore them during review.

He accumulates 15 sessions over the trip. Each session has 50-200 phone photos and he takes another 300-500 photos per day on his camera. Back in Auckland, he exports the Trip Archive (the file is about 10MB - mainly voice notes; the trajectory data and photo index are just a few hundred KB).

On his PC, he processes each batch of camera photos against the corresponding sessions. The desktop tool correctly handles the timezone difference between NZ time (his camera clock) and Japan time (the actual travel timezone) after he adjusts the offset once.

### Scenario 3: Day Hike (日间徒步)

Chris goes for a day hike in the Waitakere Ranges. He switches to "High Accuracy" mode in settings for a more detailed track. The app records GPS every 3-5 seconds. He takes photos at scenic viewpoints and records a voice note at the summit about the view.

After the hike, he reviews the session. The timeline shows precise walking segments with elevation changes. He exports just the GPX track to upload to a hiking community website, and the full archive for his records.

---

## Appendix B: Glossary (术语表)

| English Term | Chinese Term | Description |
|---|---|---|
| Trip / TripGroup | 旅行 / 旅行分组 | A collection of sessions forming one travel experience |
| Session | 会话 / 记录 | A single continuous recording period (typically one day) |
| TrackPoint | 轨迹点 | A single GPS coordinate with timestamp and metadata |
| Trajectory / Track | 轨迹 | The sequence of TrackPoints forming the recorded path |
| MediaReference | 媒体引用 | An index entry pointing to a photo/video file without copying it |
| Moment | 时刻 | User-facing term for a cluster of media at a time/location |
| Note | 笔记 | A text or voice annotation created by the user |
| Trip Index File / Trip Archive | 旅行索引文件 / 旅行档案 | The exported package containing trajectory, media index, and notes |
| EXIF Write-back | GPS写回EXIF | Writing inferred GPS coordinates into a photo file's EXIF metadata |
| Time Offset | 时间偏移 | The clock difference between the camera and the phone |
| Transport Mode | 交通方式 | The inferred mode of transport (walking, driving, etc.) |
| Foreground Service | 前台服务 | An Android service that shows a persistent notification and is less likely to be killed by the system |
| Content URI | 内容URI | Android's standard way to reference media files across apps |
| Reverse Geocoding | 反向地理编码 | Converting GPS coordinates into a human-readable place name |
