/* Drive the school's own form handlers. Never perform or bypass a security challenge. */
(function (root) {
    'use strict';
    function trusted(location) {
        return location.protocol === 'https:' && location.hostname === 'uis.nwpu.edu.cn' &&
            (!location.port || location.port === '443') && /^\/cas\/login\/?$/.test(location.pathname);
    }
    function form() { return root.document.querySelector('#fm1'); }
    function button(f) {
        return f && (f.querySelector('[name="button"]') || f.querySelector('input[type="submit"][name="submit"], button[type="submit"]'));
    }
    function visible(e) {
        return !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length) && root.getComputedStyle(e).visibility !== 'hidden';
    }
    function inspect() {
        if (!trusted(root.location)) return {phase: 'other'};
        const f = form();
        const errors = root.document.querySelectorAll('.el-message--error .el-message__content, #msg.errors, .login-error, .alert-danger');
        for (const e of errors) {
            if (!visible(e)) continue;
            const s = e.textContent || '';
            // Return fixed messages, never an arbitrary response or account value.
            if (/密码|password|凭据|用户名|账号|帐户|账户|锁定|失败|错误|incorrect|invalid/i.test(s))
                return {phase: 'error', message: /锁定|次数/.test(s) ? '学校暂时限制了登录，请稍后重试' : '学校未通过登录验证，请核对账号密码后重试'};
        }
        const challenge = root.document.querySelectorAll('.geetest_panel, .yidun_panel, .verifybox, .verify-box, .captcha-modal, #captcha_div, .el-dialog__wrapper');
        if ((root.vm && root.vm.guardDialogVisible) || Array.from(challenge).some(visible)) return {phase: 'challenge'};
        if (!f || !f.querySelector('#username') || !f.querySelector('#password') ||
            !button(f) || !root.jQuery || typeof root.mfaDetect !== 'function') return {phase: 'loading'};
        return {phase: root.__louLoginSent ? 'submitted' : 'ready'};
    }
    function setValue(e, value) {
        const setter = Object.getOwnPropertyDescriptor(root.HTMLInputElement.prototype, 'value').set;
        setter.call(e, value);
        e.dispatchEvent(new root.Event('input', {bubbles: true}));
        e.dispatchEvent(new root.Event('change', {bubbles: true}));
    }
    function submit(username, password) {
        if (!trusted(root.location) || inspect().phase !== 'ready') return {phase: 'loading'};
        const f = form();
        const action = new URL(f.getAttribute('action') || root.location.href, root.location.href);
        if (!trusted(action) || !username || !password) return {phase: 'error', message: '校方登录页面发生变化，请稍后重试'};
        if (root.vm && typeof root.vm.changeMenu === 'function') root.vm.changeMenu('1');
        setValue(f.querySelector('#username'), username);
        setValue(f.querySelector('#password'), password);
        root.__louLoginSent = true;
        // Clicking the real button preserves the school's RSA, MFA and CAPTCHA handlers.
        button(f).click();
        return {phase: 'submitted'};
    }
    const api = {trusted, inspect, submit};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.NwpuLogin = api;
})(typeof window === 'undefined' ? globalThis : window);
