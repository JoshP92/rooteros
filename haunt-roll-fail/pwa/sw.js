// RooterOS service worker.
//
// Deliberately minimal so it NEVER interferes with the game's own Cache-API asset pipeline
// (see loader.scala). It exists only to:
//   1. satisfy the PWA "installable" requirement (manifest + registered SW over HTTPS),
//   2. receive Web Push notifications ("it's your turn" / off-turn reactions like Ambush),
//   3. focus or open the right page when a notification is tapped.
//
// IMPORTANT: there is intentionally NO 'fetch' handler. The game loads hundreds of assets
// concurrently through the Cache API; routing them through a service worker — even a
// pass-through one that never calls respondWith() — can intermittently fail with
// net::ERR_FAILED when the browser terminates the idle SW mid-request. A notification-only
// SW does not need a fetch handler, and modern browsers (Chrome 89+, iOS 16.4+) do not
// require one for installability. So we register no fetch listener at all; every request
// goes straight to the network exactly as if no SW were present.

self.addEventListener('install', () => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

// Web Push. Payload is JSON: { title, body, url, tag }.
self.addEventListener('push', (event) => {
    let data = {};
    try {
        data = event.data ? event.data.json() : {};
    } catch (e) {
        data = { body: event.data ? event.data.text() : '' };
    }
    const title = data.title || 'RooterOS';
    const options = {
        body: data.body || "It's your turn!",
        icon: '/icon-192.png',
        badge: '/icon-192.png',
        tag: data.tag || 'rooteros',
        renotify: true,
        data: { url: data.url || '/games' }
    };
    event.waitUntil(self.registration.showNotification(title, options));
});

// Tapping a notification: focus an already-open tab for that game if there is one, else open it.
self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    const url = (event.notification.data && event.notification.data.url) || '/games';
    event.waitUntil((async () => {
        const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
        for (const c of clients) {
            try {
                const u = new URL(c.url);
                if (u.pathname === url || (url !== '/games' && u.pathname.startsWith(url))) {
                    await c.focus();
                    return;
                }
            } catch (e) {}
        }
        await self.clients.openWindow(url);
    })());
});
