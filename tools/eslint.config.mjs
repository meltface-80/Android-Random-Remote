// Catches the one thing `node --check` cannot: a call to something that does
// not exist.
//
// Deleting a feature from this front-end has twice left a call behind that only
// fails at runtime — once when removing Qobuz, and once when removing the wall
// display, where the cut also swallowed the settings navigation and Settings
// stopped opening entirely. Both files parsed cleanly. no-undef is the rule
// that would have failed them.
export default [
  {
    files: ["**/*.js"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "script",
      globals: Object.fromEntries(
        [
          // Browser and DOM surface the bundled page actually uses.
          "window", "document", "navigator", "location", "history", "screen",
          "localStorage", "sessionStorage", "fetch", "Request", "Response",
          "Headers", "FormData", "Blob", "File", "FileReader", "URL",
          "URLSearchParams", "AbortController", "Image", "Audio", "Option",
          "setTimeout", "clearTimeout", "setInterval", "clearInterval",
          "requestAnimationFrame", "cancelAnimationFrame", "queueMicrotask",
          "console", "alert", "confirm", "prompt", "matchMedia", "getComputedStyle",
          "MutationObserver", "IntersectionObserver", "ResizeObserver",
          "Event", "CustomEvent", "TouchEvent", "PointerEvent", "KeyboardEvent",
          "DOMParser", "XMLHttpRequest", "WebSocket", "EventSource",
          "performance", "crypto", "atob", "btoa", "structuredClone",
          "HTMLElement", "Node", "NodeList", "Element", "CanvasRenderingContext2D",
          "CSS",
        ].map((g) => [g, "readonly"])
      ),
    },
    linterOptions: { reportUnusedDisableDirectives: true },
    rules: {
      // The whole point of this config.
      "no-undef": "error",
      // A function declared twice silently loses one of them.
      "no-redeclare": "error",
      "no-dupe-keys": "error",
      "no-dupe-args": "error",
      "no-unreachable": "error",
    },
  },
  {
    // sharecard.js DECLARES ShareCard and index.html loads it first, so it is a
    // global to app.js and a declaration in its own file. Saying so in one
    // place would make one of the two an error.
    files: ["**/app.js"],
    languageOptions: { globals: { ShareCard: "readonly" } },
  },
];
