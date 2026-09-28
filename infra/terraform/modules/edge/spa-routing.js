// CloudFront Function (cloudfront-js-2.0), viewer-request, default behavior only.
// Deep links such as /games/game_synthetic_001 and /auth/callback have no file
// extension; serve the SPA shell for them. Paths whose last segment contains a
// dot (hashed /assets files, favicon, robots.txt) pass through unchanged.
// API behaviors (/api/*, /ws/*) never run this function, so API 404 problem
// details are untouched; distribution-wide custom error responses would not be.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);

  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }

  return request;
}
