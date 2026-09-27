// Service worker for the KU Women's Basketball dashboard.
//
// What it is for: a phone added this to the home screen, so it should open and
// show the last-known season even in a gym with no signal. What it must not do
// is show yesterday's numbers to someone who has signal — a stat page that
// lies quietly is worse than one that says it is offline.
//
// So: the page and its data are network-first, cache-only as a fallback. Only
// the icons and the manifest, which change about once a year, are cache-first.

// Bumping this drops every previously cached response on activate, which is
// how a stale feed already sitting in a phone's cache gets thrown away.
const VERSION = "v1";
const CACHE = `ku-wbb-${VERSION}`;

// Enough to open cold with no network. ask-data.json is deliberately not
// precached: it is cached on first successful fetch instead, so a fresh
// install never ships a stale copy of the season. Nor is the Anthropic SDK —
// "Ask about the team" needs the network to answer anything at all, so
// carrying 190 KB of it around for an offline visit would buy nothing.
const SHELL = [
  ".",
  "index.html",
  "dashboard.html",
  "manifest.json",
  "icon.svg",
  "icon-180.png",
  "icon-192.png",
  "icon-512.png",
  "icon-maskable-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    // One missing file would otherwise reject the whole install and leave the
    // page with no worker at all, so they are added individually.
    caches.open(CACHE)
      .then((cache) => Promise.allSettled(SHELL.map((url) => cache.add(url))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))
      ))
      .then(() => self.clients.claim())
      // Taking control is not enough on the visit that installs this worker:
      // the page on screen was already fetched and rendered by whatever came
      // before, from a cache this worker had no say in. So it is reloaded once,
      // now that requests go through here. This runs on activation, which
      // happens once per worker version, so it cannot loop.
      .then(() => self.clients.matchAll({ type: "window" }))
      .then((clients) => clients.forEach((client) => {
        // A client that refuses to navigate is not worth failing over.
        try { client.navigate(client.url); } catch (e) { /* ignore */ }
      }))
      .catch(() => {})
  );
});

const isIcon = (url) => /\.(png|svg)$/.test(url.pathname) || url.pathname.endsWith("manifest.json");

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // "Ask about the team" talks to api.anthropic.com, and the page can fall
  // back to raw.githubusercontent.com. Those are other origins with their own
  // cache stories — leave them to the network rather than half-caching here.
  if (url.origin !== self.location.origin) return;

  if (isIcon(url)) {
    event.respondWith(
      caches.match(request).then((hit) => hit || fetch(request).then((resp) => {
        if (resp.ok) caches.open(CACHE).then((c) => c.put(request, resp.clone()));
        return resp;
      }))
    );
    return;
  }

  // "Network-first" is not first enough on its own. A plain fetch still
  // consults the browser's HTTP cache, and GitHub Pages serves everything with
  // a max-age, so this handler could satisfy a request without a byte leaving
  // the phone and still believe it had gone to the network. That is how a
  // corrected result would stay corrected everywhere except on the phone
  // reading it.
  //
  // So the request is remade with cache: "reload", which bypasses that cache
  // outright. It covers the page as well as its data, because a stale page
  // cannot be fixed by anything the page itself does — its code is the thing
  // that is stale. A worker is the only part of this that updates promptly, so
  // it has to be the part that insists.
  //
  // Rebuilt from the URL rather than copied: a navigation request cannot be
  // passed to the Request constructor. Nothing here needs its headers.
  const netRequest = new Request(url.href, { cache: "reload" });

  // A cached response is the answer only when the network could not produce one.
  event.respondWith(
    fetch(netRequest)
      .then((resp) => {
        if (resp.ok) {
          const copy = resp.clone();
          caches.open(CACHE).then((c) => c.put(request, copy));
        }
        return resp;
      })
      .catch(() => caches.match(request).then((hit) => hit
        // A navigation that misses still has somewhere to go: the dashboard is
        // the page, not index.html, which is only a redirect to it.
        || (request.mode === "navigate" ? caches.match("dashboard.html") : undefined)
        || Response.error()))
  );
});
