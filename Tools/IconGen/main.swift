// BoomBoom icon generator. Run from the repository root:  swift Tools/IconGen/main.swift
// Draws three candidate icons, a contact sheet, and installs the chosen one into the asset catalog.
import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Candidate installed into the asset catalog (1 to 5).
let chosenCandidate = 5

let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
let fm = FileManager.default
let root = fm.currentDirectoryPath
let candDir = root + "/Tools/IconGen/candidates"
let catalog = root + "/App/Resources/Assets.xcassets"

func col(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: srgb, components: [
        CGFloat((hex >> 16) & 255) / 255, CGFloat((hex >> 8) & 255) / 255, CGFloat(hex & 255) / 255, a,
    ])!
}

func grad(_ stops: [(UInt32, CGFloat, CGFloat)]) -> CGGradient {
    // stops: (hex, alpha, location)
    CGGradient(colorsSpace: srgb, colors: stops.map { col($0.0, $0.1) } as CFArray,
               locations: stops.map { $0.2 })!
}

func opaqueContext(_ n: Int) -> CGContext {
    let c = CGContext(data: nil, width: n, height: n, bitsPerComponent: 8, bytesPerRow: 0,
                      space: srgb, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    c.interpolationQuality = .high
    c.setShouldAntialias(true)
    return c
}

func alphaContext(_ n: Int) -> CGContext {
    let c = CGContext(data: nil, width: n, height: n, bitsPerComponent: 8, bytesPerRow: 0,
                      space: srgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.interpolationQuality = .high
    return c
}

func writePNG(_ image: CGImage, _ path: String) {
    try? fm.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else { fatalError("write failed \(path)") }
}

func circle(_ c: CGPoint, _ r: CGFloat) -> CGRect { CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r) }

/// Fills the annulus between r0 and r1 with a vertical linear gradient.
func fillRing(_ ctx: CGContext, _ c: CGPoint, _ r1: CGFloat, _ r0: CGFloat, _ g: CGGradient) {
    ctx.saveGState()
    ctx.addEllipse(in: circle(c, r1)); ctx.addEllipse(in: circle(c, r0))
    ctx.clip(using: .evenOdd)
    ctx.drawLinearGradient(g, start: CGPoint(x: c.x, y: c.y + r1), end: CGPoint(x: c.x, y: c.y - r1), options: [])
    ctx.restoreGState()
}

func strokeCircle(_ ctx: CGContext, _ c: CGPoint, _ r: CGFloat, _ w: CGFloat, _ color: CGColor) {
    ctx.setStrokeColor(color); ctx.setLineWidth(w)
    ctx.strokeEllipse(in: circle(c, r))
}

/// Glossy yellow dome (dust cap) with an off-axis highlight.
func dome(_ ctx: CGContext, _ c: CGPoint, _ r: CGFloat) {
    ctx.saveGState()
    ctx.addEllipse(in: circle(c, r)); ctx.clip()
    let g = grad([(0xFFF4A8, 1, 0), (0xFFD60A, 1, 0.5), (0xD99A00, 1, 1)])
    ctx.drawRadialGradient(g, startCenter: CGPoint(x: c.x - r * 0.28, y: c.y + r * 0.32), startRadius: 0,
                           endCenter: c, endRadius: r * 1.15, options: [.drawsAfterEndLocation])
    ctx.restoreGState()
}

func glow(_ ctx: CGContext, _ c: CGPoint, _ r: CGFloat, _ a: CGFloat) {
    let g = grad([(0xFFD60A, a, 0), (0xFFD60A, 0, 1)])
    ctx.drawRadialGradient(g, startCenter: c, startRadius: 0, endCenter: c, endRadius: r, options: [])
}

/// Four shock-wave arc pairs on the diagonals.
func shockArcs(_ ctx: CGContext, _ c: CGPoint, _ R: CGFloat, count: Int, color: UInt32 = 0xFFD60A) {
    ctx.setLineCap(.round)
    for k in 0..<count {
        let r = R * (1.12 + 0.17 * CGFloat(k))
        let a = 1.0 - 0.38 * CGFloat(k)
        ctx.setStrokeColor(col(color, a)); ctx.setLineWidth(R * (0.085 - 0.02 * CGFloat(k)))
        for q in 0..<4 {
            let mid = CGFloat.pi / 4 + CGFloat(q) * .pi / 2
            let half = CGFloat.pi / 11
            ctx.addArc(center: c, radius: r, startAngle: mid - half, endAngle: mid + half, clockwise: false)
            ctx.strokePath()
        }
    }
}

// MARK: Candidate 1 — "Driver": yellow surround ring, ribbed black cone, glossy dust cap, shock arcs.

func mark1(_ ctx: CGContext, _ c: CGPoint, _ R: CGFloat, arcs: Int) {
    if arcs > 0 { shockArcs(ctx, c, R, count: arcs) }
    fillRing(ctx, c, R, R * 0.80, grad([(0xFFE45C, 1, 0), (0xFFC400, 1, 1)]))
    ctx.saveGState()
    ctx.addEllipse(in: circle(c, R * 0.74)); ctx.clip()
    ctx.drawRadialGradient(grad([(0x2E2E2E, 1, 0), (0x151515, 1, 0.7), (0x0B0B0B, 1, 1)]),
                           startCenter: CGPoint(x: c.x, y: c.y + R * 0.2), startRadius: 0,
                           endCenter: c, endRadius: R * 0.8, options: [.drawsAfterEndLocation])
    ctx.restoreGState()
    strokeCircle(ctx, c, R * 0.585, R * 0.11, col(0xFFD60A))
    dome(ctx, c, R * 0.36)
}

func icon1(_ dark: Bool) -> CGImage {
    let n = 1024, ctx = opaqueContext(n), c = CGPoint(x: 512, y: 512)
    ctx.drawRadialGradient(grad([(dark ? 0x131313 : 0x1E1E1E, 1, 0), (0x0B0B0B, 1, 1)]), startCenter: c, startRadius: 0,
                           endCenter: c, endRadius: 760, options: [.drawsAfterEndLocation])
    glow(ctx, c, 470, dark ? 0.16 : 0.22)
    mark1(ctx, c, 300, arcs: 2)
    return ctx.makeImage()!
}

// MARK: Candidate 2 — "Ripple": yellow field, black dome radiating expanding ripples.

func icon2(_ dark: Bool) -> CGImage {
    let n = 1024, ctx = opaqueContext(n), c = CGPoint(x: 512, y: 512)
    let R: CGFloat = 255
    if dark {
        ctx.setFillColor(col(0x0B0B0B)); ctx.fill(CGRect(x: 0, y: 0, width: n, height: n))
        glow(ctx, c, 560, 0.2)
        for k in 0..<5 {
            let r = R * (1.28 + 0.34 * CGFloat(k))
            strokeCircle(ctx, c, r, 34 - CGFloat(k) * 6, col(0xFFD60A, 0.85 - 0.17 * CGFloat(k)))
        }
        ctx.setFillColor(col(0x0B0B0B)); ctx.fillEllipse(in: circle(c, R * 1.12))
        fillRing(ctx, c, R, R * 0.86, grad([(0xFFE45C, 1, 0), (0xFFC400, 1, 1)]))
        dome(ctx, c, R * 0.52)
        strokeCircle(ctx, c, R * 0.68, R * 0.06, col(0xFFD60A, 0.85))
    } else {
        ctx.drawRadialGradient(grad([(0xFFE45C, 1, 0), (0xFFD60A, 1, 0.55), (0xFFC400, 1, 1)]),
                               startCenter: CGPoint(x: 400, y: 700), startRadius: 0, endCenter: c, endRadius: 800,
                               options: [.drawsAfterEndLocation])
        for k in 0..<5 {
            let r = R * (1.28 + 0.34 * CGFloat(k))
            strokeCircle(ctx, c, r, 34 - CGFloat(k) * 6, col(0x0B0B0B, 0.85 - 0.17 * CGFloat(k)))
        }
        // Soft drop shadow under the dome for depth.
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -14), blur: 40, color: col(0x000000, 0.45))
        ctx.setFillColor(col(0x0B0B0B)); ctx.fillEllipse(in: circle(c, R * 1.0))
        ctx.restoreGState()
        ctx.saveGState()
        ctx.addEllipse(in: circle(c, R)); ctx.clip()
        ctx.drawRadialGradient(grad([(0x333333, 1, 0), (0x151515, 1, 0.6), (0x0B0B0B, 1, 1)]),
                               startCenter: CGPoint(x: c.x - R * 0.3, y: c.y + R * 0.4), startRadius: 0,
                               endCenter: c, endRadius: R * 1.15, options: [.drawsAfterEndLocation])
        ctx.restoreGState()
        strokeCircle(ctx, c, R * 0.74, R * 0.05, col(0xFFD60A))
        strokeCircle(ctx, c, R * 0.55, R * 0.04, col(0xFFC400, 0.8))
        dome(ctx, c, R * 0.34)
    }
    return ctx.makeImage()!
}

// MARK: Candidate 3 — "Burst": equaliser bars radiating around a speaker cap.

func mark3(_ ctx: CGContext, _ c: CGPoint, _ R: CGFloat) {
    fillRing(ctx, c, R, R * 0.82, grad([(0xFFE45C, 1, 0), (0xFFC400, 1, 1)]))
    ctx.saveGState()
    ctx.addEllipse(in: circle(c, R * 0.76)); ctx.clip()
    ctx.drawRadialGradient(grad([(0x2E2E2E, 1, 0), (0x151515, 1, 0.7), (0x0B0B0B, 1, 1)]),
                           startCenter: CGPoint(x: c.x, y: c.y + R * 0.2), startRadius: 0,
                           endCenter: c, endRadius: R * 0.8, options: [.drawsAfterEndLocation])
    ctx.restoreGState()
    // Equaliser bars, symmetric, with a bold centre peak.
    let hs: [CGFloat] = [0.30, 0.55, 0.95, 0.55, 0.30]
    let w = R * 0.15, gap = R * 0.075
    let total = CGFloat(hs.count) * w + CGFloat(hs.count - 1) * gap
    ctx.setLineCap(.round)
    for (i, h) in hs.enumerated() {
        let x = c.x - total / 2 + w / 2 + CGFloat(i) * (w + gap)
        let half = R * 0.52 * h
        ctx.saveGState()
        let path = CGPath(roundedRect: CGRect(x: x - w / 2, y: c.y - half, width: w, height: 2 * half),
                          cornerWidth: w / 2, cornerHeight: w / 2, transform: nil)
        ctx.addPath(path); ctx.clip()
        ctx.drawLinearGradient(grad([(0xFFE45C, 1, 0), (0xFFC400, 1, 1)]),
                               start: CGPoint(x: x, y: c.y + half), end: CGPoint(x: x, y: c.y - half), options: [])
        ctx.restoreGState()
    }
}

func icon3(_ dark: Bool) -> CGImage {
    let n = 1024, ctx = opaqueContext(n), c = CGPoint(x: 512, y: 512)
    ctx.drawRadialGradient(grad([(dark ? 0x121212 : 0x1C1C1C, 1, 0), (0x0B0B0B, 1, 1)]), startCenter: c, startRadius: 0,
                           endCenter: c, endRadius: 760, options: [.drawsAfterEndLocation])
    glow(ctx, c, 430, dark ? 0.14 : 0.2)
    mark3(ctx, c, 300)
    shockArcs(ctx, c, 300, count: 2)
    return ctx.makeImage()!
}

// MARK: Candidate 4 — "Play badge": a yellow disc with a black ring and a black play mark, and BoomBoom's own shock
// arcs around it. A round play badge is the common sign of a music player; the arcs and the black-on-yellow are ours.

func mark4(_ ctx: CGContext, _ c: CGPoint, _ R: CGFloat, arcs: Int) {
    if arcs > 0 { shockArcs(ctx, c, R, count: arcs) }
    ctx.saveGState()
    ctx.addEllipse(in: circle(c, R)); ctx.clip()
    ctx.drawLinearGradient(grad([(0xFFE566, 1, 0), (0xFFD60A, 1, 0.45), (0xFFC400, 1, 1)]),
                           start: CGPoint(x: c.x, y: c.y + R), end: CGPoint(x: c.x, y: c.y - R), options: [])
    ctx.restoreGState()
    strokeCircle(ctx, c, R * 0.60, R * 0.058, col(0x0B0B0B))
    // The play mark, its corners rounded, nudged right so that it sits in the middle to the eye.
    let r = R * 0.30, centre = CGPoint(x: c.x + R * 0.045, y: c.y)
    let points = [
        CGPoint(x: centre.x + r, y: centre.y),
        CGPoint(x: centre.x - r * 0.5, y: centre.y + r * 0.866),
        CGPoint(x: centre.x - r * 0.5, y: centre.y - r * 0.866),
    ]
    let path = CGMutablePath()
    path.move(to: CGPoint(x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2))
    for i in 1...3 {
        path.addArc(tangent1End: points[i % 3], tangent2End: points[(i + 1) % 3], radius: R * 0.035)
    }
    path.closeSubpath()
    ctx.addPath(path)
    ctx.setFillColor(col(0x0B0B0B))
    ctx.fillPath()
}

func icon4(_ dark: Bool) -> CGImage {
    let n = 1024, ctx = opaqueContext(n), c = CGPoint(x: 512, y: 512)
    ctx.drawRadialGradient(grad([(dark ? 0x131313 : 0x1E1E1E, 1, 0), (0x0B0B0B, 1, 1)]), startCenter: c, startRadius: 0,
                           endCenter: c, endRadius: 760, options: [.drawsAfterEndLocation])
    glow(ctx, c, 480, dark ? 0.14 : 0.20)
    mark4(ctx, c, 306, arcs: 2)
    return ctx.makeImage()!
}

// MARK: Candidate 5 — "Pulse badge": no arcs around it. A yellow disc with a black play mark held between two black
// brackets, the two beats of the name. BoomBoom's own mark: a round play badge like many players have, drawn so
// that it is nobody else's logo.

func mark5(_ ctx: CGContext, _ c: CGPoint, _ R: CGFloat) {
    ctx.saveGState()
    ctx.addEllipse(in: circle(c, R)); ctx.clip()
    ctx.drawLinearGradient(grad([(0xFFE566, 1, 0), (0xFFD60A, 1, 0.45), (0xFFC400, 1, 1)]),
                           start: CGPoint(x: c.x, y: c.y + R), end: CGPoint(x: c.x, y: c.y - R), options: [])
    ctx.restoreGState()
    // The brackets: two arcs, open at the top and the bottom.
    ctx.setStrokeColor(col(0x0B0B0B)); ctx.setLineWidth(R * 0.085); ctx.setLineCap(.round)
    let half = CGFloat.pi * 0.27
    for middle in [CGFloat(0), CGFloat.pi] {
        ctx.addArc(center: c, radius: R * 0.64, startAngle: middle - half, endAngle: middle + half, clockwise: false)
        ctx.strokePath()
    }
    // The play mark, its corners rounded, nudged right so that it sits in the middle to the eye.
    let r = R * 0.36, centre = CGPoint(x: c.x + R * 0.05, y: c.y)
    let points = [
        CGPoint(x: centre.x + r, y: centre.y),
        CGPoint(x: centre.x - r * 0.5, y: centre.y + r * 0.866),
        CGPoint(x: centre.x - r * 0.5, y: centre.y - r * 0.866),
    ]
    let path = CGMutablePath()
    path.move(to: CGPoint(x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2))
    for i in 1...3 {
        path.addArc(tangent1End: points[i % 3], tangent2End: points[(i + 1) % 3], radius: R * 0.045)
    }
    path.closeSubpath()
    ctx.addPath(path)
    ctx.setFillColor(col(0x0B0B0B))
    ctx.fillPath()
}

func icon5(_ dark: Bool) -> CGImage {
    let n = 1024, ctx = opaqueContext(n), c = CGPoint(x: 512, y: 512)
    ctx.drawRadialGradient(grad([(dark ? 0x131313 : 0x1E1E1E, 1, 0), (0x0B0B0B, 1, 1)]), startCenter: c, startRadius: 0,
                           endCenter: c, endRadius: 760, options: [.drawsAfterEndLocation])
    glow(ctx, c, 500, dark ? 0.12 : 0.18)
    mark5(ctx, c, 372)
    return ctx.makeImage()!
}

func candidate(_ i: Int, dark: Bool = false) -> CGImage {
    switch i {
    case 1: return icon1(dark)
    case 2: return icon2(dark)
    case 3: return icon3(dark)
    case 4: return icon4(dark)
    default: return icon5(dark)
    }
}

/// Grayscale version for iOS tinted mode (still opaque).
func tinted(_ img: CGImage) -> CGImage {
    let n = img.width
    let ctx = CGContext(data: nil, width: n, height: n, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: n, height: n))
    let p = ctx.data!.assumingMemoryBound(to: UInt8.self), bpr = ctx.bytesPerRow
    for y in 0..<n { for x in 0..<n {
        let o = y * bpr + x * 4
        let l = UInt8(min(255, 0.2126 * Double(p[o]) + 0.7152 * Double(p[o + 1]) + 0.0722 * Double(p[o + 2])))
        p[o] = l; p[o + 1] = l; p[o + 2] = l; p[o + 3] = 255
    } }
    return ctx.makeImage()!
}

/// The mark alone on transparency, `px` pixels square.
func markImage(_ which: Int, px: Int) -> CGImage {
    let ctx = alphaContext(px), c = CGPoint(x: CGFloat(px) / 2, y: CGFloat(px) / 2)
    let R = CGFloat(px) * 0.5
    switch which {
    case 3: mark3(ctx, c, R * 0.99)
    case 4: mark4(ctx, c, R * 0.97, arcs: 0)
    case 5: mark5(ctx, c, R * 0.97)
    default: mark1(ctx, c, R * 0.97, arcs: 0)
    }
    return ctx.makeImage()!
}

// MARK: Contact sheet

func sheet() {
    let imgs = (3...5).map { candidate($0) }
    let W = 1900, H = 700
    let ctx = alphaContext(W); _ = ctx
    let sc = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0, space: srgb,
                       bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    sc.interpolationQuality = .high
    for (row, bg) in [0xFFFFFF, 0x000000].enumerated() {
        let y0 = CGFloat(H / 2 * (1 - row))
        sc.setFillColor(col(UInt32(bg))); sc.fill(CGRect(x: 0, y: y0, width: CGFloat(W), height: CGFloat(H / 2)))
        var x: CGFloat = 50
        for img in imgs {
            for s in [256, 120, 60] as [CGFloat] {
                let rect = CGRect(x: x, y: y0 + (CGFloat(H / 2) - s) / 2, width: s, height: s)
                sc.saveGState()
                sc.addPath(CGPath(roundedRect: rect, cornerWidth: s * 0.2237, cornerHeight: s * 0.2237, transform: nil))
                sc.clip(); sc.draw(img, in: rect); sc.restoreGState()
                x += s + 28
            }
            x += 40
        }
    }
    writePNG(sc.makeImage()!, candDir + "/sheet.png")
}

// MARK: Output

for i in 1...5 { writePNG(candidate(i), "\(candDir)/candidate-\(i).png") }
sheet()

let iconSet = catalog + "/AppIcon.appiconset"
writePNG(candidate(chosenCandidate), iconSet + "/AppIcon-1024.png")
let darkImg = candidate(chosenCandidate, dark: true)
writePNG(darkImg, iconSet + "/AppIcon-Dark-1024.png")
writePNG(tinted(darkImg), iconSet + "/AppIcon-Tinted-1024.png")

func write(_ s: String, _ path: String) {
    try? fm.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    try! s.write(toFile: path, atomically: true, encoding: .utf8)
}
let info = #""info" : { "author" : "xcode", "version" : 1 }"#

write("{ \(info) }\n", catalog + "/Contents.json")
write("""
{
  "images" : [
    { "filename" : "AppIcon-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" },
    { "appearances" : [ { "appearance" : "luminosity", "value" : "dark" } ],
      "filename" : "AppIcon-Dark-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" },
    { "appearances" : [ { "appearance" : "luminosity", "value" : "tinted" } ],
      "filename" : "AppIcon-Tinted-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
  ],
  \(info)
}

""", iconSet + "/Contents.json")

func colorset(_ name: String, _ r: String, _ g: String, _ b: String) {
    write("""
    {
      "colors" : [
        { "color" : { "color-space" : "srgb", "components" : { "alpha" : "1.000", "blue" : "\(b)", "green" : "\(g)", "red" : "\(r)" } },
          "idiom" : "universal" },
        { "appearances" : [ { "appearance" : "luminosity", "value" : "dark" } ],
          "color" : { "color-space" : "srgb", "components" : { "alpha" : "1.000", "blue" : "\(b)", "green" : "\(g)", "red" : "\(r)" } },
          "idiom" : "universal" }
      ],
      \(info)
    }

    """, "\(catalog)/\(name).colorset/Contents.json")
}
colorset("AccentColor", "1.000", "0.839", "0.039")      // #FFD60A
colorset("LaunchBackground", "0.043", "0.043", "0.043") // #0B0B0B

let markSet = catalog + "/BoomMark.imageset"
var markEntries: [String] = []
for s in 1...3 {
    let name = "BoomMark@\(s)x.png"
    writePNG(markImage(chosenCandidate, px: 28 * s), "\(markSet)/\(name)")
    markEntries.append(#"    { "filename" : "\#(name)", "idiom" : "universal", "scale" : "\#(s)x" }"#)
}
write("{\n  \"images\" : [\n" + markEntries.joined(separator: ",\n") + "\n  ],\n  \(info)\n}\n", markSet + "/Contents.json")
print("done")
