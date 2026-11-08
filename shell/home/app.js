const list = document.getElementById("services");
const statusText = document.getElementById("status");

function showError(error) {
    statusText.textContent = error.message;
}

document.getElementById("settings").addEventListener("click", () => {
    bridge("home.openSettings").catch(showError);
});

bridge("home.services").then((services) => {
    for (const {id, title} of services) {
        const item = document.createElement("li");
        const tile = document.createElement("button");
        tile.type = "button";
        tile.className = "tile";

        const mark = document.createElement("span");
        mark.className = "tile-mark";
        mark.setAttribute("aria-hidden", "true");
        mark.textContent = Array.from(title.trim())[0].toUpperCase();

        const label = document.createElement("span");
        label.textContent = title;
        const version = toolVersions[id];
        if (Number.isInteger(version)) {
            const versionLabel = document.createElement("small");
            versionLabel.className = "tile-version";
            versionLabel.textContent = `v${version}`;
            label.append(versionLabel);
        }
        const arrow = document.createElement("span");
        arrow.className = "tile-arrow";
        arrow.setAttribute("aria-hidden", "true");
        arrow.textContent = "↗";

        tile.append(mark, label, arrow);
        tile.addEventListener("click", () => bridge("home.openTool", {serviceId: id}).catch(showError));
        item.append(tile);
        list.append(item);
    }
    statusText.textContent = services.length ? "" : "No tools downloaded yet. Refresh the catalog in Settings.";
}).catch(showError);
