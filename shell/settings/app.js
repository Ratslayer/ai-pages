const statusText = document.getElementById("status");
const connection = document.getElementById("connection");
const buttons = new Map(Array.from(document.querySelectorAll("[data-action]"), (button) => [
    button.dataset.action, button,
]));

function showError(error) {
    statusText.textContent = error.message;
}

for (const [action, button] of buttons) {
    button.addEventListener("click", () => bridge("settings." + action).catch(showError));
}

bridge("settings.state").then(({connected, signingIn, canFinishSignIn, updating, status}) => {
    const busy = updating || signingIn;
    connection.textContent = signingIn ? "Connecting…" : connected ? "Connected" : "Disconnected";
    connection.dataset.connected = String(connected);
    connection.hidden = false;
    statusText.textContent = status;
    buttons.get("signIn").hidden = connected || signingIn;
    buttons.get("testConnection").hidden = !connected || signingIn;
    buttons.get("disconnect").hidden = !connected || signingIn;
    buttons.get("pasteRedirect").hidden = !canFinishSignIn;
    buttons.get("stopSignIn").hidden = !canFinishSignIn;
    for (const action of ["signIn", "testConnection", "disconnect", "refresh", "importCatalog", "configureUpdates"]) {
        buttons.get(action).disabled = busy;
    }
}).catch(showError);
