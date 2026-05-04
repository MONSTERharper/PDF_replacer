const contactForm = document.querySelector("#contactForm");
if (contactForm) {
  contactForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const name = document.querySelector("#contactName").value.trim();
    const email = document.querySelector("#contactEmail").value.trim();
    const subject = document.querySelector("#contactSubject").value.trim();
    const message = document.querySelector("#contactMessage").value.trim();
    const status = document.querySelector("#contactStatus");

    if (!name || !email || !subject || !message) {
      status.textContent = "Please fill all fields.";
      status.className = "status error";
      return;
    }

    try {
      const response = await fetch("/api/contact", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, email, subject, message })
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(payload.message || `Request failed with ${response.status}`);
      }
      status.textContent = "Inquiry sent successfully.";
      status.className = "status ok";
      contactForm.reset();
    } catch (error) {
      status.textContent = error.message || "Failed to send inquiry.";
      status.className = "status error";
    }
  });
}
