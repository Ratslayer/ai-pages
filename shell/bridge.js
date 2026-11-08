"use strict";

const bridge = (() => {
    let nextId = 0;
    const pending = new Map();
    const native = window.AiPages;

    if (native) {
        native.onmessage = (event) => {
            const {id, value, error} = JSON.parse(event.data);
            const finish = pending.get(id);
            if (finish) finish(error, value);
        };
    }

    return (method, args) => new Promise((resolve, reject) => {
        if (!native) {
            reject(new Error("Open this page in AI Pages."));
            return;
        }
        const id = String(++nextId);
        const timer = setTimeout(() => {
            pending.delete(id);
            reject(new Error("AI Pages did not respond. Try again or open Recovery."));
        }, 3000);
        pending.set(id, (error, value) => {
            clearTimeout(timer);
            pending.delete(id);
            if (error) reject(new Error(error));
            else resolve(value);
        });
        native.postMessage(JSON.stringify({...args, id, method}));
    });
})();
