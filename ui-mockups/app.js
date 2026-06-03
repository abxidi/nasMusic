const pauseIcon = '<svg><use href="#icon-pause"></use></svg>';
const playIcon = '<svg><use href="#icon-play"></use></svg>';

document.querySelectorAll("[data-toggle-play]").forEach((button) => {
  button.addEventListener("click", () => {
    const isPaused = button.dataset.state === "paused";
    button.dataset.state = isPaused ? "playing" : "paused";
    button.innerHTML = isPaused ? pauseIcon : playIcon;

    document.querySelectorAll(".record-wrap").forEach((record) => {
      record.classList.toggle("is-playing", isPaused);
    });
  });
});
