// RooterOS service worker.
//
// Deliberately minimal so it NEVER interferes with the game's own Cache-API asset pipeline
// (see loader.scala). It exists only to:
//   1. satisfy the PWA "installable" requirement (manifest + SW + fetch handler over HTTPS),
//   2. receive Web Push notifications ("it's your turn" / off-turn reactions like Ambush),
//   3. focus or open the right page when a notification is tapped.
//
// It does NOT cache or rewrite /hrf/* asset requests — the fetch handler is a pass-through.

self.addEventListener('install', () => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

// Pass-through fetch handler. Registering *a* fetch handler is what makes the app installable on
// some browsers; by never calling event.respondWith() we let the browser handle every request
// exactly as it would without a service worker (so the game's own asset caching is untouched).
self.addEventListener('fetch', () => {});

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
