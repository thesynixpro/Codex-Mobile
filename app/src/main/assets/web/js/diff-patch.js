// Codex Mobile - Safe AI Code Diff & Patch Engine
const DiffPatch = {
  // Parse proposed code changes from AI response text
  extractProposals(aiText) {
    const proposals = [];
    if (!aiText) return proposals;

    const seenPaths = new Set();

    // Pattern 1: Explicit Action + File + Code block
    // ### Action: CREATE|MODIFY
    // ### File: path/to/file.ext
    // ```lang ... ```
    const actionFileRegex = /(?:###?\s*Action:\s*(CREATE|MODIFY|UPDATE)\s*\n)?(?:###?\s*(?:File|FILE):\s*`?([a-zA-Z0-9_\-./]+)`?|(?:File|FILE):\s*`?([a-zA-Z0-9_\-./]+)`?)\s*```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/gi;
    let match;
    while ((match = actionFileRegex.exec(aiText)) !== null) {
      const explicitAction = match[1] ? (match[1].toUpperCase() === 'UPDATE' ? 'MODIFY' : match[1].toUpperCase()) : null;
      const filePath = (match[2] || match[3] || '').trim().replace(/^[\/\\]+/, '');
      const lang = match[4] || '';
      const proposedContent = match[5];

      if (filePath && proposedContent !== undefined && !seenPaths.has(filePath)) {
        seenPaths.add(filePath);
        const exists = window.FileSystem && !!window.FileSystem.files[filePath];
        const action = explicitAction || (exists ? 'MODIFY' : 'CREATE');
        proposals.push({
          action,
          filePath,
          proposedContent: proposedContent.replace(/\r\n/g, '\n'),
          language: lang
        });
      }
    }

    // Pattern 2: Explicit Deletion markers
    // ### Action: DELETE\n### File: path/to/file.ext
    // or ### DELETE: path/to/file.ext
    const deleteRegex = /(?:###?\s*Action:\s*DELETE\s*\n###?\s*File:\s*`?([a-zA-Z0-9_\-./]+)`?|###?\s*DELETE:\s*`?([a-zA-Z0-9_\-./]+)`?)/gi;
    while ((match = deleteRegex.exec(aiText)) !== null) {
      const filePath = (match[1] || match[2] || '').trim().replace(/^[\/\\]+/, '');
      if (filePath && !seenPaths.has(filePath)) {
        seenPaths.add(filePath);
        proposals.push({
          action: 'DELETE',
          filePath,
          proposedContent: '',
          language: ''
        });
      }
    }

    // Pattern 3: Code block with lang:path e.g. ```html:index.html
    if (proposals.length === 0) {
      const fenceLangRegex = /```([a-zA-Z0-9_-]+):([a-zA-Z0-9_\-./]+)\n([\s\S]*?)```/g;
      while ((match = fenceLangRegex.exec(aiText)) !== null) {
        const lang = match[1];
        const filePath = (match[2] || '').trim().replace(/^[\/\\]+/, '');
        const proposedContent = match[3];
        if (filePath && proposedContent !== undefined && !seenPaths.has(filePath)) {
          seenPaths.add(filePath);
          const exists = window.FileSystem && !!window.FileSystem.files[filePath];
          proposals.push({
            action: exists ? 'MODIFY' : 'CREATE',
            filePath,
            proposedContent: proposedContent.replace(/\r\n/g, '\n'),
            language: lang
          });
        }
      }
    }

    // Pattern 4: If single code block and active file is open
    if (proposals.length === 0 && window.FileSystem && window.FileSystem.activeFilePath) {
      const singleFenceRegex = /```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g;
      const firstFence = singleFenceRegex.exec(aiText);
      if (firstFence && firstFence[2]) {
        const activePath = window.FileSystem.activeFilePath;
        proposals.push({
          action: 'MODIFY',
          filePath: activePath,
          proposedContent: firstFence[2].replace(/\r\n/g, '\n'),
          language: firstFence[1] || 'text'
        });
      }
    }

    return proposals;
  },

  // Generate line-by-line diff comparing original with proposed
  computeLineDiff(originalText = '', newText = '') {
    const origLines = originalText ? originalText.split('\n') : [];
    const newLines = newText ? newText.split('\n') : [];

    const m = origLines.length;
    const n = newLines.length;
    const dp = Array(m + 1).fill(0).map(() => Array(n + 1).fill(0));

    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (origLines[i - 1] === newLines[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
        }
      }
    }

    const diff = [];
    let i = m;
    let j = n;

    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && origLines[i - 1] === newLines[j - 1]) {
        diff.unshift({ type: 'unchanged', text: origLines[i - 1] });
        i--;
        j--;
      } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
        diff.unshift({ type: 'added', text: newLines[j - 1] });
        j--;
      } else if (i > 0 && (j === 0 || dp[i][j - 1] < dp[i - 1][j])) {
        diff.unshift({ type: 'removed', text: origLines[i - 1] });
        i--;
      }
    }

    return diff;
  },

  // Render comprehensive Change Preview container with batch & single controls
  renderChangePreview(proposals, onApplyAll, onRejectAll) {
    const container = document.createElement('div');
    container.className = 'chat-proposals-container';

    if (!proposals || proposals.length === 0) return container;

    const creates = proposals.filter(p => p.action === 'CREATE').length;
    const modifies = proposals.filter(p => p.action === 'MODIFY').length;
    const deletes = proposals.filter(p => p.action === 'DELETE').length;

    const projectName = window.FileSystem && window.FileSystem.currentProject
      ? window.FileSystem.currentProject.name
      : 'Project';

    // Summary Header Banner
    const banner = document.createElement('div');
    banner.className = 'proposals-banner';
    banner.innerHTML = `
      <div class="proposals-banner-title">
        <span class="banner-icon">${Icons.sparkles}</span>
        <strong>Proposed Project Changes (${proposals.length} file${proposals.length > 1 ? 's' : ''})</strong>
      </div>
      <div class="proposals-stats-row">
        ${creates > 0 ? `<span class="badge-pill badge-create">+${creates} to create</span>` : ''}
        ${modifies > 0 ? `<span class="badge-pill badge-modify">~${modifies} to modify</span>` : ''}
        ${deletes > 0 ? `<span class="badge-pill badge-delete">-${deletes} to delete</span>` : ''}
        <span class="target-folder-label">Target: ${this.escapeHtml(projectName)}</span>
      </div>
    `;
    container.appendChild(banner);

    // List of File Proposal Cards
    const cardsWrapper = document.createElement('div');
    cardsWrapper.className = 'proposals-cards-list';

    for (let idx = 0; idx < proposals.length; idx++) {
      const prop = proposals[idx];
      const card = this.renderSingleProposalCard(prop, projectName);
      cardsWrapper.appendChild(card);
    }
    container.appendChild(cardsWrapper);

    // Master Batch Action Bar with "Apply Changes"
    const actionBar = document.createElement('div');
    actionBar.className = 'proposals-action-bar';
    actionBar.innerHTML = `
      <div class="action-bar-status" id="action-bar-status">
        Ready to review and apply directly to <strong>${this.escapeHtml(projectName)}</strong>
      </div>
      <div class="action-bar-buttons">
        <button type="button" class="btn btn-outline btn-reject-all" title="Reject all changes">
          ${Icons.close}
          <span>Reject All</span>
        </button>
        <button type="button" class="btn btn-primary btn-apply-all" title="Write changes directly into project folder">
          ${Icons.check}
          <span>Apply Changes (${proposals.length})</span>
        </button>
      </div>
    `;

    const applyAllBtn = actionBar.querySelector('.btn-apply-all');
    const rejectAllBtn = actionBar.querySelector('.btn-reject-all');
    const statusText = actionBar.querySelector('.action-bar-status');

    applyAllBtn.addEventListener('click', async () => {
      applyAllBtn.disabled = true;
      rejectAllBtn.disabled = true;
      applyAllBtn.innerHTML = `<span class="btn-spinner"></span> <span>Writing to ${this.escapeHtml(projectName)}...</span>`;
      statusText.innerHTML = `Applying files directly inside <strong>${this.escapeHtml(projectName)}</strong>...`;

      try {
        await onApplyAll(proposals);
        applyAllBtn.className = 'btn btn-success applied-btn';
        applyAllBtn.innerHTML = `${Icons.check} <span>Changes Applied</span>`;
        statusText.innerHTML = `${Icons.check} Successfully written directly into <strong>${this.escapeHtml(projectName)}</strong>.`;
      } catch (err) {
        applyAllBtn.disabled = false;
        rejectAllBtn.disabled = false;
        applyAllBtn.innerHTML = `${Icons.check} <span>Retry Apply Changes</span>`;
        statusText.innerHTML = `<span class="text-danger">Failed: ${this.escapeHtml(err.message)}</span>`;
      }
    });

    rejectAllBtn.addEventListener('click', () => {
      container.classList.add('proposals-rejected');
      container.innerHTML = `
        <div class="proposals-dismissed-banner">
          ${Icons.close} Proposed changes dismissed. No files were modified.
        </div>
      `;
      if (onRejectAll) onRejectAll(proposals);
    });

    container.appendChild(actionBar);
    return container;
  },

  renderSingleProposalCard(proposal, projectName) {
    const card = document.createElement('div');
    card.className = `diff-proposal-card action-${proposal.action.toLowerCase()}`;

    const currentContent = window.FileSystem ? window.FileSystem.getFileContent(proposal.filePath) || '' : '';
    const diffLines = proposal.action === 'DELETE'
      ? []
      : this.computeLineDiff(currentContent, proposal.proposedContent);

    let additions = 0;
    let deletions = 0;
    diffLines.forEach(l => {
      if (l.type === 'added') additions++;
      if (l.type === 'removed') deletions++;
    });

    const badgeClass = proposal.action === 'CREATE' ? 'badge-create' : proposal.action === 'DELETE' ? 'badge-delete' : 'badge-modify';
    const badgeLabel = proposal.action === 'CREATE' ? 'NEW FILE' : proposal.action === 'DELETE' ? 'DELETE' : 'MODIFY';

    const header = document.createElement('div');
    header.className = 'diff-card-header';
    header.innerHTML = `
      <div class="diff-file-meta">
        <span class="badge-pill ${badgeClass}">${badgeLabel}</span>
        <span class="diff-icon">${Icons.fileCode}</span>
        <span class="diff-filename" title="${this.escapeHtml(proposal.filePath)}">${this.escapeHtml(proposal.filePath)}</span>
        ${proposal.action !== 'DELETE' ? `<span class="diff-stats"><span class="stat-add">+${additions}</span> <span class="stat-del">-${deletions}</span></span>` : ''}
      </div>
      <button type="button" class="diff-toggle-view-btn" title="Toggle diff details">
        ${Icons.chevronDown}
      </button>
    `;

    const body = document.createElement('div');
    body.className = 'diff-card-body';

    if (proposal.action === 'DELETE') {
      body.innerHTML = `
        <div class="diff-delete-notice">
          ${Icons.alertTriangle}
          <span>This file will be permanently deleted from the selected project directory when applied.</span>
        </div>
      `;
    } else {
      let diffHtml = '<div class="diff-viewer">';
      // Show lines
      const maxDisplayLines = 200;
      for (let i = 0; i < Math.min(diffLines.length, maxDisplayLines); i++) {
        const line = diffLines[i];
        const safeText = this.escapeHtml(line.text || ' ');
        if (line.type === 'added') {
          diffHtml += `<div class="diff-line line-added"><span class="diff-indicator">+</span><span class="diff-content">${safeText}</span></div>`;
        } else if (line.type === 'removed') {
          diffHtml += `<div class="diff-line line-removed"><span class="diff-indicator">-</span><span class="diff-content">${safeText}</span></div>`;
        } else {
          diffHtml += `<div class="diff-line line-neutral"><span class="diff-indicator"> </span><span class="diff-content">${safeText}</span></div>`;
        }
      }
      if (diffLines.length > maxDisplayLines) {
        diffHtml += `<div class="diff-truncated-note">... ${diffLines.length - maxDisplayLines} more lines ...</div>`;
      }
      diffHtml += '</div>';
      body.innerHTML = diffHtml;
    }

    // Toggle expansion
    const toggleBtn = header.querySelector('.diff-toggle-view-btn');
    toggleBtn.addEventListener('click', () => {
      const isCollapsed = body.classList.toggle('collapsed');
      toggleBtn.innerHTML = isCollapsed ? Icons.chevronRight : Icons.chevronDown;
    });

    card.appendChild(header);
    card.appendChild(body);
    return card;
  },

  escapeHtml(str) {
    if (!str) return '';
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }
};

window.DiffPatch = DiffPatch;
