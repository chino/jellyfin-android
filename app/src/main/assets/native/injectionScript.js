(() => {
    // Shim to fix crash on Jellyfin Server 10.11.x
    // Prevents "TypeError: behavior 'null' is not a valid enum value" in scrollTo
    const fixScrollOptions = (options) => {
        if (options && typeof options === 'object' && options.behavior === null) {
            options.behavior = 'auto';
        }
    };

    try {
        const originalElementScrollTo = Element.prototype.scrollTo;
        Element.prototype.scrollTo = function(options) {
            fixScrollOptions(options);
            return originalElementScrollTo.apply(this, arguments);
        };

        const originalWindowScrollTo = window.scrollTo;
        window.scrollTo = function(options) {
            fixScrollOptions(options);
            return originalWindowScrollTo.apply(this, arguments);
        };
    } catch (e) {}

    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        document.currentScript.src.concat('?deferred=true&ts=', Date.now())
    ];
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        scriptElement.setAttribute('defer', '');
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();
})();
