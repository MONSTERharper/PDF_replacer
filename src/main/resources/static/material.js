const form = document.querySelector("#replaceForm");
const statusEl = document.querySelector("#status");
const replaceScope = document.querySelector("#replaceScope");
const occurrenceField = document.querySelector("#occurrenceField");
const occurrenceInput = document.querySelector("#occurrenceIndex");
const submitButton = document.querySelector("#submitButton");
const changesContainer = document.querySelector("#changesContainer");
const addChangeButton = document.querySelector("#addChangeButton");
const toolItems = Array.from(document.querySelectorAll(".tool-item"));
const replacePanel = document.querySelector("#replacePanel");
const toolInfoTitle = document.querySelector("#toolInfoTitle");
const toolInfoText = document.querySelector("#toolInfoText");
const suiteToggleButton = document.querySelector("#suiteToggleButton");
const suiteOptions = document.querySelector("#suiteOptions");

const toolDescriptions = {
  replace: "Replace is live and ready to use.",
  merge: "Merge is in progress. Soon you will combine multiple PDFs into one output.",
  split: "Split is in progress. Soon you will split one PDF into selected page ranges.",
  compress: "Compress is in progress. Soon you will reduce PDF size while keeping quality.",
  convert: "Convert is in progress. Soon you will convert PDFs to and from common formats.",
  sign: "Sign is in progress. Soon you will add digital signatures to documents.",
  extract: "Extract is in progress. Soon you will extract text, pages, and assets from PDFs."
};

function activateTool(toolKey, status) {
  toolItems.forEach((item) => item.classList.remove("active"));
  const active = toolItems.find((item) => item.dataset.tool === toolKey);
  if (active) active.classList.add("active");
  toolInfoTitle.textContent = `PDFBolt ${toolKey.charAt(0).toUpperCase()}${toolKey.slice(1)}`;
  toolInfoText.textContent = toolDescriptions[toolKey] || "This tool is in progress.";
  if (status === "live") {
    replacePanel.hidden = false;
  } else {
    replacePanel.hidden = true;
    statusEl.textContent = `PDFBolt ${toolKey} is coming soon.`;
    statusEl.className = "status";
  }
}

toolItems.forEach((item) => {
  item.addEventListener("click", () => activateTool(item.dataset.tool, item.dataset.status));
});

if (suiteToggleButton && suiteOptions) {
  suiteToggleButton.addEventListener("click", () => {
    const nextHidden = !suiteOptions.hidden;
    suiteOptions.hidden = nextHidden;
    suiteToggleButton.setAttribute("aria-expanded", String(!nextHidden));
  });
}

function createChangeRow(find = "", replace = "") {
  const row = document.createElement("div");
  row.className = "row";
  row.innerHTML = `
    <label class="field">
      <span>Find</span>
      <input class="searchInput" type="text" value="${find}" required>
    </label>
    <label class="field">
      <span>Replace with</span>
      <input class="replacementInput" type="text" value="${replace}">
    </label>
    <button type="button" class="remove-change">Delete</button>
  `;
  const removeButton = row.querySelector(".remove-change");
  removeButton.addEventListener("click", () => {
    const totalRows = changesContainer.querySelectorAll(".row").length;
    if (totalRows <= 1) {
      statusEl.textContent = "At least one find/replace rule is required.";
      statusEl.className = "status error";
      return;
    }
    row.remove();
  });
  return row;
}

addChangeButton.addEventListener("click", () => {
  changesContainer.appendChild(createChangeRow());
});
changesContainer.appendChild(createChangeRow());

function syncOccurrenceField() {
  const requiresOccurrence = replaceScope.value === "nth";
  occurrenceField.hidden = !requiresOccurrence;
  occurrenceInput.required = requiresOccurrence;
  if (!requiresOccurrence) {
    occurrenceInput.value = "";
  }
}

replaceScope.addEventListener("change", syncOccurrenceField);
syncOccurrenceField();
activateTool("replace", "live");

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  statusEl.textContent = "Processing PDF...";
  statusEl.className = "status";
  submitButton.disabled = true;

  try {
    const data = new FormData();
    const filesInput = document.querySelector("#filesInput");
    if (!filesInput.files || filesInput.files.length === 0) {
      throw new Error("Please select at least one PDF file.");
    }
    for (const file of filesInput.files) {
      data.append("files", file);
    }

    const searchInputs = Array.from(document.querySelectorAll(".searchInput"));
    const replacementInputs = Array.from(document.querySelectorAll(".replacementInput"));
    if (searchInputs.length === 0) {
      throw new Error("Add at least one find/replace rule.");
    }
    for (let i = 0; i < searchInputs.length; i++) {
      const search = searchInputs[i].value.trim();
      if (!search) {
        throw new Error("Find text cannot be empty.");
      }
      data.append("search", search);
      data.append("replacement", replacementInputs[i].value || "");
    }

    const fontInput = form.querySelector('input[name="font"]');
    const font = fontInput && fontInput.files && fontInput.files[0] ? fontInput.files[0] : null;
    if (font) {
      data.append("font", font);
    }
    data.append("matchMode", form.matchMode.value);
    data.append("replaceScope", form.replaceScope.value);
    data.set("strict", form.strict.checked ? "true" : "false");
    data.set("preserveStyle", form.preserveStyle.checked ? "true" : "false");
    if (data.get("replaceScope") !== "nth") {
      data.delete("occurrenceIndex");
    }

    const response = await fetch("/api/replace", { method: "POST", body: data });
    if (!response.ok) {
      let message = `Request failed with ${response.status}`;
      const contentType = response.headers.get("content-type") || "";
      if (contentType.includes("application/json")) {
        const payload = await response.json();
        message = payload.message || message;
      } else {
        const text = await response.text();
        if (text) {
          message = text;
        }
      }
      throw new Error(message);
    }

    const blob = await response.blob();
    const disposition = response.headers.get("content-disposition") || "";
    const match = disposition.match(/filename="?([^";]+)"?/i);
    const filename = match ? match[1] : "replaced.pdf";
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);

    const matches = response.headers.get("X-Pdf-Replacer-Matches");
    const found = response.headers.get("X-Pdf-Replacer-Matches-Found");
    const preserved = response.headers.get("X-Pdf-Replacer-Style-Preserved");
    const fallback = response.headers.get("X-Pdf-Replacer-Style-Fallback");
    statusEl.textContent = `Done. ${matches || "0"} replacement(s) applied from ${found || "0"} match(es). Style-preserved: ${preserved || "0"}, fallback-font: ${fallback || "0"}.`;
    statusEl.className = "status ok";
  } catch (error) {
    statusEl.textContent = error.message;
    statusEl.className = "status error";
  } finally {
    submitButton.disabled = false;
  }
});
