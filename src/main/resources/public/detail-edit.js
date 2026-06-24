// Inline meta editing for the issue detail fragment (#issue-detail), shared by
// the full issue page and the Kanban modal. All handlers are document-level and
// guard on #issue-detail being present, so they're harmless when it isn't.
(function () {
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
