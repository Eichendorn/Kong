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
    // Focus the control as soon as an editor is swapped in. Listen on document
    // (not document.body) — this script is loaded in <head> on the Kanban page,
    // so document.body is still null when it runs; htmx:afterSwap bubbles here.
    document.addEventListener('htmx:afterSwap', function (e) {
        var t = e.target;
        var editor = (t.classList && t.classList.contains('inline-editor'))
                ? t : (t.querySelector && t.querySelector('.inline-editor'));
        if (editor) { var f = editor.querySelector('input, select'); if (f) f.focus(); }
    });
})();

// @-mention autocomplete for the comment box. The comment form lives inside
// #issue-detail, which htmx swaps wholesale, so everything is delegated at the
// document level and re-resolves the current textarea/dropdown on each event.
// Picking someone inserts an "@[Display Name](accountId)" token, which the
// server turns into a proper ADF mention node — that's what makes Jira email the
// mentioned user. Plain "@name" typed without picking stays literal text.
(function () {
    var timer = null;
    var atPos = -1;        // index of the '@' that opened the active query
    var items = [];        // current .mention-item buttons
    var sel = -1;          // highlighted item index

    function isTa(el) { return el && el.classList && el.classList.contains('mention-input'); }
    function dropdownFor(ta) {
        var wrap = ta.closest('.mention-wrap');
        return wrap ? wrap.querySelector('.mention-dropdown') : null;
    }
    function hide(ta) {
        var dd = dropdownFor(ta);
        if (dd) { dd.hidden = true; dd.innerHTML = ''; }
        atPos = -1; items = []; sel = -1;
    }
    // The active "@query" ending at the caret, or null if the caret isn't in one.
    function queryAt(ta) {
        var upto = ta.value.slice(0, ta.selectionStart);
        var at = upto.lastIndexOf('@');
        if (at < 0) return null;
        if (at > 0 && !/\s/.test(upto.charAt(at - 1))) return null; // must start on a boundary
        var q = upto.slice(at + 1);
        if (q.indexOf('\n') >= 0) return null;   // a mention query never spans lines
        if (q.charAt(0) === '[') return null;     // sitting just after an inserted token
        return { at: at, q: q };
    }
    function highlight(idx) {
        sel = idx;
        items.forEach(function (b, i) { b.classList.toggle('active', i === idx); });
    }
    function render(ta, html) {
        var dd = dropdownFor(ta);
        if (!dd) return;
        dd.innerHTML = html;
        items = Array.prototype.slice.call(dd.querySelectorAll('.mention-item'));
        if (!items.length) { hide(ta); return; }
        dd.hidden = false;
        highlight(0);
    }
    function search(ta, q) {
        fetch('/users/mention-suggest?q=' + encodeURIComponent(q))
            .then(function (r) { return r.text(); })
            .then(function (html) {
                var now = queryAt(ta);           // ignore stale responses
                if (now && now.at === atPos) render(ta, html);
            })
            .catch(function () { /* ignore */ });
    }
    function pick(ta, btn) {
        if (!btn || atPos < 0) return;
        var token = '@[' + btn.getAttribute('data-name') + '](' + btn.getAttribute('data-account') + ') ';
        var before = ta.value.slice(0, atPos);
        var after = ta.value.slice(ta.selectionStart);
        ta.value = before + token + after;
        var caret = before.length + token.length;
        hide(ta);
        ta.focus();
        ta.setSelectionRange(caret, caret);
    }

    document.addEventListener('input', function (e) {
        var ta = e.target;
        if (!isTa(ta)) return;
        var m = queryAt(ta);
        if (!m || m.q.length < 1) { hide(ta); return; }
        atPos = m.at;
        clearTimeout(timer);
        timer = setTimeout(function () { search(ta, m.q); }, 180);
    });

    document.addEventListener('keydown', function (e) {
        var ta = e.target;
        if (!isTa(ta)) return;
        var dd = dropdownFor(ta);
        if (!dd || dd.hidden || !items.length) return;
        if (e.key === 'ArrowDown') { e.preventDefault(); highlight((sel + 1) % items.length); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); highlight((sel - 1 + items.length) % items.length); }
        else if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); pick(ta, items[sel] || items[0]); }
        else if (e.key === 'Escape') { e.preventDefault(); hide(ta); }
    });

    // Click a suggestion (mousedown + preventDefault so the textarea keeps focus).
    document.addEventListener('mousedown', function (e) {
        var btn = e.target.closest ? e.target.closest('.mention-item') : null;
        if (!btn) return;
        e.preventDefault();
        var wrap = btn.closest('.mention-wrap');
        var ta = wrap ? wrap.querySelector('.mention-input') : null;
        if (ta) pick(ta, btn);
    });

    // Dismiss shortly after the textarea loses focus (lets a click land first).
    document.addEventListener('focusout', function (e) {
        if (isTa(e.target)) setTimeout(function () { hide(e.target); }, 150);
    });
})();
