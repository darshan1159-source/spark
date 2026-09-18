// app.js — thin JS utilities not covered inline in templates.
// htmx handles all AJAX; this file is intentionally minimal.

document.addEventListener('DOMContentLoaded', () => {

    // -----------------------------------------------------------------
    // Template tab highlight sync
    // The buttons are outside the htmx swap target, so we update them client-side.
    // -----------------------------------------------------------------
    const tplTabs = document.querySelectorAll('.tpl-tab');
    tplTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tplTabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
        });
    });

    // -----------------------------------------------------------------
    // Skills tag live preview (client-side only — no round-trip needed)
    // -----------------------------------------------------------------
    const skillsTextarea = document.getElementById('skillsInput');
    const skillTags      = document.getElementById('skill-tags');

    if (skillsTextarea && skillTags) {
        const skillTagsEmpty = document.getElementById('skill-tags-empty');
        skillsTextarea.addEventListener('input', () => {
            const skills = skillsTextarea.value.split(',').map(s => s.trim()).filter(Boolean);
            // Match the server-rendered tag style exactly (border-2 border-ink font-bold py-1.5) —
            // this used to be a stale bg-indigo-50 style that visibly mismatched the real tags
            // the instant a user typed, then snapped back on the next server round-trip.
            const esc = window.escapeHtml || (s => s);
            skillTags.innerHTML = skills.map(s =>
                `<span class="border-2 border-ink text-ink text-xs font-bold px-3 py-1.5 rounded-full">${esc(s)}</span>`
            ).join('');
            if (skillTagsEmpty) skillTagsEmpty.classList.toggle('hidden', skills.length > 0);
        });
    }

    // -----------------------------------------------------------------
    // Section tab active state update after htmx navigation
    // (Tabs are non-htmx, but section content may get re-rendered.)
    // -----------------------------------------------------------------
    // Already handled by showSection() in builder.html inline <script>.
});
