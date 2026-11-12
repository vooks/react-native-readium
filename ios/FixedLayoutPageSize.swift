import Foundation
import ReadiumShared

struct FixedLayoutPageSize: Hashable {
  let width: Double
  let height: Double
}

private let sampledPages = 3
private let headBytes: UInt64 = 16384

private let metaTag = try! NSRegularExpression(pattern: "<meta\\b[^>]*>", options: [.caseInsensitive])
private let viewportName = try! NSRegularExpression(pattern: "name\\s*=\\s*[\"']viewport[\"']", options: [.caseInsensitive])
private let contentAttr = try! NSRegularExpression(pattern: "content\\s*=\\s*[\"']([^\"']*)[\"']", options: [.caseInsensitive])
private let widthValue = try! NSRegularExpression(pattern: "(?:^|[,;\\s])width\\s*=\\s*(\\d+(?:\\.\\d+)?)", options: [.caseInsensitive])
private let heightValue = try! NSRegularExpression(pattern: "(?:^|[,;\\s])height\\s*=\\s*(\\d+(?:\\.\\d+)?)", options: [.caseInsensitive])

private func firstGroup(_ regex: NSRegularExpression, in text: String) -> String? {
  let range = NSRange(text.startIndex..., in: text)
  guard let match = regex.firstMatch(in: text, options: [], range: range),
        match.numberOfRanges > 1,
        let groupRange = Range(match.range(at: 1), in: text) else {
    return nil
  }
  return String(text[groupRange])
}

func parseViewportPageSize(_ html: String) -> FixedLayoutPageSize? {
  let range = NSRange(html.startIndex..., in: html)
  for match in metaTag.matches(in: html, options: [], range: range) {
    guard let tagRange = Range(match.range, in: html) else { continue }
    let tag = String(html[tagRange])
    let tagNSRange = NSRange(tag.startIndex..., in: tag)
    guard viewportName.firstMatch(in: tag, options: [], range: tagNSRange) != nil,
          let content = firstGroup(contentAttr, in: tag),
          let width = firstGroup(widthValue, in: content).flatMap(Double.init),
          let height = firstGroup(heightValue, in: content).flatMap(Double.init),
          width > 0, height > 0 else {
      continue
    }
    return FixedLayoutPageSize(width: width, height: height)
  }
  return nil
}

private func pageSize(of link: Link, in publication: Publication) async -> FixedLayoutPageSize? {
  if let width = link.width, let height = link.height, width > 0, height > 0 {
    return FixedLayoutPageSize(width: Double(width), height: Double(height))
  }
  guard let resource = publication.get(link) else { return nil }
  defer { resource.close() }
  guard case .success(let data) = await resource.read(range: 0..<headBytes) else {
    return nil
  }
  return parseViewportPageSize(String(decoding: data, as: UTF8.self))
}

func fixedLayoutPageSize(_ publication: Publication) async -> FixedLayoutPageSize? {
  guard publication.metadata.layout == .fixed else { return nil }
  var counts: [FixedLayoutPageSize: Int] = [:]
  var order: [FixedLayoutPageSize] = []
  for link in publication.readingOrder.prefix(sampledPages) {
    guard let size = await pageSize(of: link, in: publication) else { continue }
    if counts[size] == nil { order.append(size) }
    counts[size, default: 0] += 1
  }
  return order.max { (counts[$0] ?? 0) < (counts[$1] ?? 0) }
}
