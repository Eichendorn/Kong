// Inline meta editing for the issue detail fragment (#issue-detail), shared by
// the full issue page and the Kanban modal. All handlers are document-level and
// guard on #issue-detail being present, so they're harmless when it isn't.
(function () {
    // Copy a Jira browse link to the clipboard. Capturing phase + stopPropagation
    // so clicking the icon inside a Kanban card doesn't also open the modal.
    function flash(btn, cls) {
        btn.classList.add(cls);
        setTimeout(function () { btn.classList.remove(cls); }, 1200);
    }
    function legacyCopy(text) {
        var ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); } catch (err) { /* ignore */ }
        document.body.removeChild(ta);
    }
    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(text).catch(function () { legacyCopy(text); });
        }
        legacyCopy(text);
        return Promise.resolve();
    }
    // Left-click: copy the real Jira browse link (green flash).
    document.addEventListener('click', function (e) {
        var btn = e.target.closest('.copy-link');
        if (!btn) return;
        e.preventDefault();
        e.stopPropagation();
        copyText(btn.getAttribute('data-jira-url')).then(function () { flash(btn, 'copied'); });
    }, true);
    // Right-click: copy this app's detail link (orange flash); suppress the context menu.
    document.addEventListener('contextmenu', function (e) {
        var btn = e.target.closest('.copy-link');
        if (!btn) return;
        e.preventDefault();
        e.stopPropagation();
        var appUrl = window.location.origin + '/issue/' + btn.getAttribute('data-key');
        copyText(appUrl).then(function () { flash(btn, 'copied-mgr'); });
    }, true);
    // Open the task in the Jira app in a new tab (capture phase so a card's
    // click/dblclick doesn't also fire).
    document.addEventListener('click', function (e) {
        var btn = e.target.closest('.open-jira');
        if (!btn) return;
        e.preventDefault();
        e.stopPropagation();
        window.open(btn.getAttribute('data-jira-url'), '_blank', 'noopener');
    }, true);

    document.addEventListener('click', function (e) {
        // Picking a user suggestion saves the assignee/reporter.
        var pick = e.target.closest('.inline-editor .suggest-item');
        if (pick) {
            htmx.ajax('POST', pick.getAttribute('data-url'), {
                target: '#issue-detail', swap: 'outerHTML',
                values: { accountId: pick.getAttribute('data-account') }
            });
            return;
        }
        // Clicking a field value loads its editor (handled by the element's hx-get).
        if (e.target.closest('.inline-edit')) return;
        // Clicking outside an open editor restores the read-only view.
        var pane = document.getElementById('issue-detail');
        var openEditor = pane && pane.querySelector('.inline-editor');
        if (openEditor && !openEditor.contains(e.target)) {
            htmx.ajax('GET', pane.getAttribute('data-detail-url'),
                      { target: '#issue-detail', swap: 'outerHTML' });
        }
    });
    // Focus the control as soon as an editor is swapped in.
    document.body.addEventListener('htmx:afterSwap', function (e) {
        var t = e.target;
        var editor = (t.classList && t.classList.contains('inline-editor'))
                ? t : (t.querySelector && t.querySelector('.inline-editor'));
        if (editor) { var f = editor.querySelector('input, select'); if (f) f.focus(); }
    });
})();
