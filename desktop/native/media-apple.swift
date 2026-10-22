// Trawl's video and sound reader for macOS: AVFoundation, the decoders every Mac already has (nothing is bundled).
//
// It stays running and answers one request per line on standard input, the way ocr-stdin.swift does, so the cost of starting it is paid
// once. Requests are tab-separated:
//   probe  PATH
//   frame  PATH  TIME_MS  MAX_SIDE
//   audio  PATH  START_MS  SPAN_MS
// Every answer starts with one JSON line on standard output. A picture (JPEG) or a stretch of sound (16 kHz, mono, 16-bit little-endian
// PCM) follows it as length-prefixed chunks: 4 bytes big-endian length, then that many bytes; a length of 0 ends the answer. The same
// protocol is written by media-windows.ps1, so one reader in the app serves both and either can be tried by hand:
//   printf 'probe\t/some/clip.mp4\n' | media-apple
//
// Compile: swiftc -O -target arm64-apple-macos13.0 media-apple.swift -o media-apple
// Error text is kept short and never repeats the file's path or name.

import AVFoundation
import Foundation
import ImageIO
import CoreMedia

setvbuf(stdout, nil, _IOFBF, 1 << 16)

func writeBytes(_ p: UnsafeRawPointer, _ n: Int) {
  var off = 0
  while off < n {
    let w = fwrite(p + off, 1, n - off, stdout)
    if w <= 0 { exit(1) }   // the app went away
    off += w
  }
}

func writeLine(_ obj: [String: Any]) {
  let data = (try? JSONSerialization.data(withJSONObject: obj, options: [])) ?? Data("{\"ok\":false,\"error\":\"internal\"}".utf8)
  data.withUnsafeBytes { if let b = $0.baseAddress { writeBytes(b, data.count) } }
  writeBytes([UInt8(10)], 1)
  fflush(stdout)
}

func fail(_ why: String) { writeLine(["ok": false, "error": why, "chunked": false]) }

func writeChunk(_ p: UnsafeRawPointer, _ n: Int) {
  var len = UInt32(n).bigEndian
  withUnsafeBytes(of: &len) { writeBytes($0.baseAddress!, 4) }
  writeBytes(p, n)
}

func endChunks() {
  var zero = UInt32(0).bigEndian
  withUnsafeBytes(of: &zero) { writeBytes($0.baseAddress!, 4) }
  fflush(stdout)
}

func openAsset(_ path: String) -> AVURLAsset? {
  guard FileManager.default.fileExists(atPath: path) else { return nil }
  return AVURLAsset(url: URL(fileURLWithPath: path), options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
}

func probe(_ path: String) async {
  guard let asset = openAsset(path) else { return fail("no such file") }
  guard let (readable, duration) = try? await asset.load(.isReadable, .duration), readable else { return fail("not a media file") }
  let seconds = CMTimeGetSeconds(duration)
  // A cover picture inside an audio file is a video track with one frame; it has no length of its own.
  var video = false
  for t in (try? await asset.loadTracks(withMediaType: .video)) ?? [] {
    guard let (range, rate) = try? await t.load(.timeRange, .nominalFrameRate) else { continue }
    if (range.duration.isValid && CMTimeGetSeconds(range.duration) > 0.5) || rate > 1 { video = true; break }
  }
  let audio = !(((try? await asset.loadTracks(withMediaType: .audio)) ?? []).isEmpty)
  if !video && !audio { return fail("no picture or sound in it") }
  var out: [String: Any] = ["ok": true, "hasVideo": video, "hasAudio": audio, "chunked": false]
  if seconds.isFinite && seconds > 0 { out["durationMs"] = Int((seconds * 1000).rounded()) }
  writeLine(out)
}

func frame(_ path: String, _ ms: Int64, _ maxSide: Int) async {
  guard let asset = openAsset(path) else { return fail("no such file") }
  guard let (readable, dur) = try? await asset.load(.isReadable, .duration), readable,
        let videoTracks = try? await asset.loadTracks(withMediaType: .video), !videoTracks.isEmpty else { return fail("no picture in it") }
  let gen = AVAssetImageGenerator(asset: asset)
  gen.appliesPreferredTrackTransform = true            // upright, as the person sees it
  gen.requestedTimeToleranceBefore = .positiveInfinity // the nearest keyframe: much faster than the exact frame, and the phone does the same
  gen.requestedTimeToleranceAfter = .positiveInfinity
  let side = CGFloat(max(32, maxSide))
  gen.maximumSize = CGSize(width: side, height: side)  // a bounding box: the picture keeps its shape
  var at = CMTime(value: max(0, ms), timescale: 1000)
  if dur.isValid && CMTimeCompare(at, dur) >= 0 { at = CMTimeMaximum(.zero, CMTimeSubtract(dur, CMTime(value: 100, timescale: 1000))) }
  guard let image = try? await gen.image(at: at).image else { return fail("no picture there") }
  let data = NSMutableData()
  guard let dest = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return fail("could not encode") }
  CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
  guard CGImageDestinationFinalize(dest), data.length > 0 else { return fail("could not encode") }
  writeLine(["ok": true, "chunked": true, "width": image.width, "height": image.height])
  writeChunk(data.bytes, data.length)
  endChunks()
}

func audio(_ path: String, _ startMs: Int64, _ spanMs: Int64) async {
  guard let asset = openAsset(path) else { return fail("no such file") }
  guard let readable = try? await asset.load(.isReadable), readable else { return fail("not a media file") }
  let tracks = (try? await asset.loadTracks(withMediaType: .audio)) ?? []
  if tracks.isEmpty { writeLine(["ok": true, "chunked": true]); endChunks(); return }   // no sound is not an error: it is silence
  guard let reader = try? AVAssetReader(asset: asset) else { return fail("could not read the sound") }
  let settings: [String: Any] = [
    AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1,
    AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
  ]
  // The mix output takes every sound track, mixes them down to mono and resamples to 16 kHz.
  let output = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: settings)
  guard reader.canAdd(output) else { return fail("could not read the sound") }
  reader.add(output)
  reader.timeRange = CMTimeRange(start: CMTime(value: max(0, startMs), timescale: 1000), duration: CMTime(value: max(1, spanMs), timescale: 1000))
  guard reader.startReading() else { return fail("could not read the sound") }
  writeLine(["ok": true, "chunked": true])
  var scratch = [UInt8](repeating: 0, count: 1 << 16)
  while let sample = output.copyNextSampleBuffer() {
    guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
    let total = CMBlockBufferGetDataLength(block)
    if total > scratch.count { scratch = [UInt8](repeating: 0, count: total) }
    if total > 0, CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: total, destination: &scratch) == kCMBlockBufferNoErr {
      scratch.withUnsafeBytes { writeChunk($0.baseAddress!, total) }
    }
  }
  endChunks()   // a read that failed half-way simply ends early: the app keeps the sound it got
}

while let line = readLine(strippingNewline: true) {
  let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
  guard let cmd = f.first, !cmd.isEmpty else { continue }
  switch cmd {
  case "probe" where f.count >= 2: await probe(f[1])
  case "frame" where f.count >= 4: await frame(f[1], Int64(f[2]) ?? 0, Int(f[3]) ?? 336)
  case "audio" where f.count >= 4: await audio(f[1], Int64(f[2]) ?? 0, Int64(f[3]) ?? 15_000)
  case "quit": exit(0)
  default: fail("bad request")
  }
}
