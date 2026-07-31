(() => {
    "use strict";

    const API = "/api/jobs";
    const STORAGE_KEY = "modDpBridge.lastJobId";
    const MAX_TERMINAL_LINES = 6000;
    const REPORT_BATCH_SIZE = 120;
    const TERMINAL_STATES = new Set(["succeeded", "failed", "cancelled"]);

    const $ = (selector, root = document) => root.querySelector(selector);
    const elements = {
        serviceState: $("#service-state"),
        serviceStateText: $("#service-state-text"),
        fileInput: $("#mod-file"),
        fileHelp: $("#file-help"),
        dropZone: $("#drop-zone"),
        selectedFile: $("#selected-file"),
        fileGlyph: $("#file-glyph"),
        selectedFileName: $("#selected-file-name"),
        selectedFileMeta: $("#selected-file-meta"),
        removeFile: $("#remove-file"),
        uploadError: $("#upload-error"),
        startButton: $("#start-button"),
        cancelButton: $("#cancel-button"),
        jobStatus: $("#job-status"),
        phaseLabel: $("#phase-label"),
        progressValue: $("#progress-value"),
        progressTrack: $("#progress-track"),
        progressBar: $("#progress-bar"),
        jobId: $("#job-id"),
        elapsedTime: $("#elapsed-time"),
        terminalOutput: $("#terminal-output"),
        autoScroll: $("#auto-scroll"),
        copyLog: $("#copy-log"),
        downloadResult: $("#download-result"),
        downloadLogs: $("#download-logs"),
        reportDetails: $("#report-details"),
        reportSummaryText: $("#report-summary-text"),
        reportOverall: $("#report-overall"),
        reportLoading: $("#report-loading"),
        reportContent: $("#report-content"),
        reportNotice: $("#report-notice"),
        validationCount: $("#validation-count"),
        validationList: $("#validation-list"),
        resultGroups: $("#result-groups"),
        diagnosticsCount: $("#diagnostics-count"),
        diagnosticsList: $("#diagnostics-list"),
        diagnosticSearch: $("#diagnostic-search"),
        inventoryCount: $("#inventory-count"),
        inventoryContent: $("#inventory-content"),
        toastRegion: $("#toast-region"),
    };

    const state = {
        file: null,
        job: null,
        jobId: null,
        eventSource: null,
        uploadRequest: null,
        elapsedTimer: null,
        startedAt: null,
        terminalLines: 0,
        terminalTruncated: false,
        finishingJobId: null,
        eventRecoveryTimer: null,
        report: null,
        diagnosticMap: new Map(),
        servicePoll: null,
        maxUploadBytes: null,
        jobGeneration: 0,
    };

    const statusPresentation = {
        idle: ["等待文件", "status-idle"],
        uploading: ["正在上传", "status-running"],
        queued: ["排队中", "status-running"],
        running: ["转换中", "status-running"],
        succeeded: ["转换完成", "status-success"],
        failed: ["转换失败", "status-failed"],
        cancelled: ["已终止", "status-cancelled"],
    };

    const reportPresentation = {
        success: ["完整完成", "status-success"],
        partial: ["部分完成", "status-partial"],
        rejected: ["已拒绝", "status-failed"],
        failed: ["报告失败", "status-failed"],
    };

    const phasePresentation = {
        queued: "等待可用转换队列",
        starting: "正在启动转换器",
        reading: "正在读取上传的 Mod",
        scanning: "正在扫描并验证文件",
        detecting: "正在识别输入格式",
        exporting: "正在静态提取内容声明",
        planning: "正在规划内容和资源",
        "writing-assets": "正在写入服务器数据资源",
        "writing-zip": "正在生成 Data Pack ZIP",
        validating: "正在执行转换验证",
        finalizing: "正在生成报告与诊断",
        cancelling: "正在终止转换进程",
        cancelled: "转换已终止",
        completed: "转换完成",
        failed: "转换失败",
    };

    const dispositionPresentation = {
        converted: { label: "已转换", className: "result-converted", color: "var(--green)" },
        degraded: { label: "降级实现", className: "result-degraded", color: "var(--orange)" },
        excluded: { label: "范围外 / 已移除", className: "result-excluded", color: "var(--blue)" },
        unsupported: { label: "暂不支持", className: "result-unsupported", color: "var(--violet)" },
        failed: { label: "转换失败", className: "result-failed", color: "var(--red)" },
    };

    function createElement(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = String(text);
        return node;
    }

    function safeJson(value) {
        if (typeof value !== "string") return value ?? {};
        try { return JSON.parse(value); } catch { return { message: value }; }
    }

    function clampProgress(value) {
        const number = Number(value);
        return Number.isFinite(number) ? Math.max(0, Math.min(100, number)) : 0;
    }

    function formatBytes(bytes) {
        const value = Number(bytes);
        if (!Number.isFinite(value) || value < 0) return "未知大小";
        if (value < 1024) return `${value} B`;
        const units = ["KiB", "MiB", "GiB"];
        let amount = value / 1024;
        let unit = units[0];
        for (let index = 1; index < units.length && amount >= 1024; index += 1) {
            amount /= 1024;
            unit = units[index];
        }
        return `${amount >= 10 ? amount.toFixed(1) : amount.toFixed(2)} ${unit}`;
    }

    function formatElapsed(milliseconds) {
        const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        return hours > 0
            ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
            : `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
    }

    function formatClock(value) {
        const date = value ? new Date(value) : new Date();
        const valid = Number.isFinite(date.getTime()) ? date : new Date();
        return valid.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
    }

    function showToast(message, type = "info", duration = 3200) {
        const toast = createElement("div", `toast toast-${type}`, message);
        elements.toastRegion.append(toast);
        window.setTimeout(() => toast.remove(), duration);
    }

    function showUploadError(message) {
        elements.uploadError.textContent = message;
        elements.uploadError.hidden = !message;
    }

    function setStatus(status, overrideText) {
        const [defaultText, className] = statusPresentation[status] || [status || "未知", "status-idle"];
        elements.jobStatus.className = `status-badge ${className}`;
        elements.jobStatus.textContent = overrideText || defaultText;
    }

    function setReportStatus(status) {
        const [text, className] = reportPresentation[status] || [status || "未知", "status-idle"];
        elements.reportOverall.className = `status-badge ${className}`;
        elements.reportOverall.textContent = text;
    }

    function setProgress(value, phase, running = false) {
        const progress = clampProgress(value);
        elements.progressBar.style.width = `${progress}%`;
        elements.progressValue.textContent = `${Math.round(progress)}%`;
        elements.progressTrack.setAttribute("aria-valuenow", String(Math.round(progress)));
        elements.progressTrack.classList.toggle("is-running", running);
        if (phase) elements.phaseLabel.textContent = phasePresentation[phase] || phase;
    }

    function saveJobId(id) {
        try {
            if (id) localStorage.setItem(STORAGE_KEY, id);
            else localStorage.removeItem(STORAGE_KEY);
        } catch { /* Storage can be disabled without breaking conversion. */ }
    }

    function readSavedJobId() {
        try { return localStorage.getItem(STORAGE_KEY); } catch { return null; }
    }

    function beginJobGeneration() {
        state.jobGeneration += 1;
        return state.jobGeneration;
    }

    function isCurrentGeneration(generation) {
        return state.jobGeneration === generation;
    }

    function isCurrentJob(jobId, generation) {
        return isCurrentGeneration(generation)
            && jobId != null
            && state.jobId != null
            && String(state.jobId) === String(jobId);
    }

    function applyJobIfCurrent(job, source, jobId, generation) {
        if (!isCurrentJob(jobId, generation)) return false;
        if (job?.id != null && String(job.id) !== String(jobId)) return false;
        applyJob(job, source);
        return true;
    }

    function setDropZoneLocked(locked) {
        elements.fileInput.disabled = locked;
        elements.removeFile.disabled = locked;
        elements.dropZone.classList.toggle("is-disabled", locked);
        elements.dropZone.setAttribute("aria-disabled", String(locked));
    }

    function updateActionState() {
        const active = Boolean(state.uploadRequest) || (state.job && !TERMINAL_STATES.has(String(state.job.status).toLowerCase()));
        const cancelling = Boolean(state.job && String(state.job.phase || "").toLowerCase() === "cancelling");
        elements.startButton.disabled = active || !state.file;
        elements.cancelButton.disabled = !active || cancelling;
        setDropZoneLocked(active);
    }

    function enableDownload(anchor, enabled, href) {
        anchor.classList.toggle("is-disabled", !enabled);
        anchor.setAttribute("aria-disabled", String(!enabled));
        anchor.href = enabled && href ? href : "#";
        if (enabled) anchor.removeAttribute("tabindex");
        else anchor.setAttribute("tabindex", "-1");
    }

    function updateDownloads(job) {
        if (!job || !state.jobId) {
            enableDownload(elements.downloadResult, false);
            enableDownload(elements.downloadLogs, false);
            return;
        }
        const root = `${API}/${encodeURIComponent(state.jobId)}/download`;
        const terminal = TERMINAL_STATES.has(String(job.status || "").toLowerCase());
        enableDownload(elements.downloadResult, Boolean(job.resultAvailable), `${root}/result`);
        enableDownload(elements.downloadLogs, terminal && Boolean(job.logsAvailable), `${root}/logs`);
    }

    function selectFile(file) {
        if (!file || elements.fileInput.disabled) return;
        const allowed = ["zip", "jar", "hjson", "json", "json5"];
        const extension = file.name.includes(".") ? file.name.split(".").pop().toLowerCase() : "";
        if (!allowed.includes(extension)) {
            showUploadError("不支持该文件类型。请选择 .zip、.jar、.hjson、.json 或 .json5 文件。");
            return;
        }
        if (file.size === 0) {
            showUploadError("所选文件为空，无法转换。");
            return;
        }
        if (state.maxUploadBytes && file.size > state.maxUploadBytes) {
            showUploadError(`所选文件为 ${formatBytes(file.size)}，超过服务器上限 ${formatBytes(state.maxUploadBytes)}。`);
            return;
        }
        state.file = file;
        elements.selectedFileName.textContent = file.name;
        elements.selectedFileMeta.textContent = `${formatBytes(file.size)} · ${extension.toUpperCase()}`;
        elements.fileGlyph.textContent = extension.toUpperCase().slice(0, 5);
        elements.selectedFile.hidden = false;
        elements.dropZone.style.minHeight = "185px";
        showUploadError("");
        updateActionState();
    }

    function removeSelectedFile() {
        if (elements.fileInput.disabled) return;
        state.file = null;
        elements.fileInput.value = "";
        elements.selectedFile.hidden = true;
        elements.dropZone.style.removeProperty("min-height");
        showUploadError("");
        updateActionState();
    }

    function resetTerminal(message = "等待转换任务…") {
        elements.terminalOutput.replaceChildren();
        delete elements.terminalOutput.dataset.truncationNotified;
        state.terminalLines = 0;
        state.terminalTruncated = false;
        appendLog({ message, level: "muted", timestamp: null });
    }

    function normalizeLog(payload) {
        const data = safeJson(payload);
        const rawLevel = String(data.level || data.severity || data.type || "").toLowerCase();
        let level = "";
        if (rawLevel.includes("error") || rawLevel === "stderr") level = "error";
        else if (rawLevel.includes("warn")) level = "warning";
        else if (rawLevel.includes("success")) level = "success";
        else if (rawLevel.includes("system") || rawLevel.includes("phase")) level = "system";
        else if (rawLevel.includes("info")) level = "info";
        const message = data.message ?? data.line ?? data.text ?? data.log ?? (typeof payload === "string" ? payload : JSON.stringify(payload));
        const text = String(message ?? "");
        if (!level && /(^|\W)(error|failed|exception|fatal)(\W|$)/i.test(text)) level = "error";
        else if (!level && /(^|\W)(warn|warning|partial|degraded|unsupported)(\W|$)/i.test(text)) level = "warning";
        else if (!level && /completed|success|passed/i.test(text)) level = "success";
        return { message: text, level, timestamp: data.timestamp || data.time || data.at };
    }

    function appendLog(payload) {
        const entry = payload && Object.hasOwn(payload, "message") ? payload : normalizeLog(payload);
        const line = createElement("div", `terminal-line ${entry.level ? `line-${entry.level}` : ""}`);
        line.append(createElement("span", "line-time", entry.timestamp === null ? "--:--:--" : formatClock(entry.timestamp)));
        line.append(createElement("span", "", entry.message));
        elements.terminalOutput.append(line);
        state.terminalLines += 1;

        while (state.terminalLines > MAX_TERMINAL_LINES && elements.terminalOutput.firstElementChild) {
            elements.terminalOutput.firstElementChild.remove();
            state.terminalLines -= 1;
            state.terminalTruncated = true;
        }
        if (state.terminalTruncated && !elements.terminalOutput.dataset.truncationNotified) {
            elements.terminalOutput.dataset.truncationNotified = "true";
            showToast("页面仅保留最近 6000 行；完整日志请下载 Logs ZIP。", "info", 5000);
        }
        if (elements.autoScroll.checked) elements.terminalOutput.scrollTop = elements.terminalOutput.scrollHeight;
    }

    function startElapsedTimer(startValue) {
        stopElapsedTimer();
        const parsed = startValue ? new Date(startValue).getTime() : Date.now();
        state.startedAt = Number.isFinite(parsed) ? parsed : Date.now();
        const update = () => { elements.elapsedTime.textContent = formatElapsed(Date.now() - state.startedAt); };
        update();
        state.elapsedTimer = window.setInterval(update, 1000);
    }

    function stopElapsedTimer(finishedValue, startValue) {
        if (state.elapsedTimer) window.clearInterval(state.elapsedTimer);
        state.elapsedTimer = null;
        const parsedStart = startValue ? new Date(startValue).getTime() : state.startedAt;
        if (Number.isFinite(parsedStart)) state.startedAt = parsedStart;
        if (Number.isFinite(parsedStart) && finishedValue) {
            const finished = new Date(finishedValue).getTime();
            if (Number.isFinite(finished)) elements.elapsedTime.textContent = formatElapsed(finished - parsedStart);
        }
    }

    function applyJob(job, source = "status") {
        if (!job || typeof job !== "object") return;
        state.job = { ...(state.job || {}), ...job };
        const current = state.job;
        const status = String(current.status || "queued").toLowerCase();
        state.jobId = current.id || state.jobId;
        if (state.jobId) {
            elements.jobId.textContent = state.jobId;
            elements.jobId.title = state.jobId;
            saveJobId(state.jobId);
        }

        setStatus(status, current.message && status === "failed" ? "转换失败" : undefined);
        setProgress(current.progress, current.phase || statusPresentation[status]?.[0], status === "queued" || status === "running");
        updateDownloads(current);
        updateActionState();

        if ((status === "queued" || status === "running") && !state.elapsedTimer) {
            startElapsedTimer(current.startedAt || current.createdAt);
        }
        if (TERMINAL_STATES.has(status)) {
            stopElapsedTimer(current.finishedAt, current.startedAt || current.createdAt);
            finishJob(current, source, state.jobGeneration);
        }
    }

    function prepareNewRun() {
        const generation = beginJobGeneration();
        closeEvents();
        state.job = null;
        state.jobId = null;
        state.finishingJobId = null;
        state.report = null;
        state.diagnosticMap.clear();
        saveJobId(null);
        stopElapsedTimer();
        elements.elapsedTime.textContent = "00:00";
        elements.jobId.textContent = "—";
        elements.reportDetails.open = false;
        elements.reportLoading.hidden = false;
        elements.reportLoading.querySelector("p").textContent = "报告将在转换结束后载入。";
        elements.reportContent.hidden = true;
        elements.reportOverall.className = "status-badge status-idle";
        elements.reportOverall.textContent = "尚无报告";
        elements.reportSummaryText.textContent = "转换完成后，可在这里核对每一项结果";
        setProgress(0, "准备上传", false);
        setStatus("uploading");
        updateDownloads(null);
        resetTerminal("已创建上传会话，等待服务器接收文件…");
        return generation;
    }

    function parseErrorResponse(xhr) {
        let payload = xhr.response;
        if (!payload) {
            try { payload = xhr.responseText; } catch { payload = ""; }
        }
        const data = safeJson(payload || "");
        return data.message || data.error || `请求失败（HTTP ${xhr.status || 0}）`;
    }

    function startConversion() {
        if (!state.file || state.uploadRequest) return;
        const generation = prepareNewRun();
        showUploadError("");

        const form = new FormData();
        form.append("file", state.file, state.file.name);
        const request = new XMLHttpRequest();
        state.uploadRequest = request;
        updateActionState();
        request.open("POST", API);
        request.responseType = "json";

        request.upload.addEventListener("progress", (event) => {
            if (!isCurrentGeneration(generation) || state.uploadRequest !== request) return;
            if (!event.lengthComputable) {
                setProgress(0, "正在上传 Mod…", true);
                return;
            }
            const percent = (event.loaded / event.total) * 100;
            setProgress(percent, `正在上传 · ${formatBytes(event.loaded)} / ${formatBytes(event.total)}`, true);
        });

        request.addEventListener("load", () => {
            if (!isCurrentGeneration(generation) || state.uploadRequest !== request) return;
            state.uploadRequest = null;
            if (request.status < 200 || request.status >= 300) {
                handleStartFailure(parseErrorResponse(request));
                return;
            }
            let job = request.response;
            if (!job) {
                try { job = safeJson(request.responseText); } catch { job = null; }
            }
            if (!job || !job.id) {
                handleStartFailure("服务器未返回有效任务编号。");
                return;
            }
            const jobId = String(job.id);
            state.jobId = jobId;
            appendLog({ message: `上传完成，任务 ${job.id} 已创建。`, level: "system" });
            // Upload and conversion use different progress scales. Reset without a
            // backwards animation, then continue with the converter's real value.
            elements.progressBar.style.transition = "none";
            applyJobIfCurrent(job, "upload", jobId, generation);
            window.requestAnimationFrame(() => elements.progressBar.style.removeProperty("transition"));
            connectEvents(jobId, generation);
        });

        request.addEventListener("error", () => {
            if (!isCurrentGeneration(generation) || state.uploadRequest !== request) return;
            state.uploadRequest = null;
            handleStartFailure("无法连接转换服务，请检查服务器状态后重试。");
        });
        request.addEventListener("abort", () => {
            if (!isCurrentGeneration(generation) || state.uploadRequest !== request) return;
            state.uploadRequest = null;
            setStatus("cancelled", "上传已终止");
            setProgress(0, "上传已由用户终止", false);
            appendLog({ message: "上传已由用户终止。", level: "warning" });
            updateActionState();
        });
        request.send(form);
    }

    function handleStartFailure(message) {
        setStatus("failed", "启动失败");
        setProgress(0, "未能创建转换任务", false);
        appendLog({ message, level: "error" });
        showUploadError(message);
        showToast(message, "error", 5200);
        updateActionState();
    }

    function closeEvents() {
        if (state.eventSource) state.eventSource.close();
        state.eventSource = null;
        if (state.eventRecoveryTimer) window.clearTimeout(state.eventRecoveryTimer);
        state.eventRecoveryTimer = null;
    }

    function connectEvents(jobId, generation = state.jobGeneration) {
        if (!isCurrentJob(jobId, generation)) return;
        closeEvents();
        if (!jobId || typeof EventSource === "undefined") {
            appendLog({ message: "浏览器不支持实时日志，将改用状态轮询。", level: "warning" });
            pollUntilFinished(jobId, generation);
            return;
        }
        const events = new EventSource(`${API}/${encodeURIComponent(jobId)}/events`);
        state.eventSource = events;
        const isCurrentConnection = () => isCurrentJob(jobId, generation) && state.eventSource === events;

        events.addEventListener("open", () => {
            if (!isCurrentConnection()) return;
            appendLog({ message: "实时日志连接已建立。", level: "system" });
        }, { once: true });
        events.addEventListener("snapshot", (event) => {
            if (!isCurrentConnection()) return;
            const payload = safeJson(event.data);
            applyJobIfCurrent(payload.job || payload, "snapshot", jobId, generation);
        });
        events.addEventListener("log", (event) => {
            if (isCurrentConnection()) appendLog(event.data);
        });
        events.addEventListener("progress", (event) => {
            if (!isCurrentConnection()) return;
            const payload = safeJson(event.data);
            const update = payload.job || { progress: payload.progress ?? payload.percent, phase: payload.phase || payload.message };
            applyJobIfCurrent(update, "progress", jobId, generation);
        });
        events.addEventListener("status", (event) => {
            if (!isCurrentConnection()) return;
            const payload = safeJson(event.data);
            applyJobIfCurrent(payload.job || payload, "status", jobId, generation);
        });
        events.addEventListener("error", () => {
            if (!isCurrentConnection()) return;
            if (state.job && TERMINAL_STATES.has(String(state.job.status).toLowerCase())) {
                closeEvents();
                return;
            }
            elements.phaseLabel.textContent = "实时连接中断，正在自动重连…";
            if (!state.eventRecoveryTimer) {
                state.eventRecoveryTimer = window.setTimeout(async () => {
                    state.eventRecoveryTimer = null;
                    if (!isCurrentConnection()) return;
                    try {
                        const snapshot = await fetchJob(jobId);
                        if (!isCurrentConnection()) return;
                        applyJobIfCurrent(snapshot, "event-recovery", jobId, generation);
                    } catch { /* EventSource continues its own retry loop. */ }
                }, 700);
            }
        });
    }

    async function pollUntilFinished(jobId, generation = state.jobGeneration) {
        if (!isCurrentJob(jobId, generation)) return;
        while (isCurrentJob(jobId, generation) && (!state.job || !TERMINAL_STATES.has(String(state.job.status).toLowerCase()))) {
            await new Promise((resolve) => window.setTimeout(resolve, 2000));
            if (!isCurrentJob(jobId, generation)) return;
            try {
                const snapshot = await fetchJob(jobId);
                if (!isCurrentJob(jobId, generation)) return;
                applyJobIfCurrent(snapshot, "poll", jobId, generation);
            } catch { /* Retry until terminal. */ }
        }
    }

    async function fetchJob(jobId) {
        const response = await fetch(`${API}/${encodeURIComponent(jobId)}`, { headers: { Accept: "application/json" } });
        if (!response.ok) throw new Error((await readError(response)) || `HTTP ${response.status}`);
        return response.json();
    }

    async function readError(response) {
        try {
            const data = await response.json();
            return data.message || data.error;
        } catch {
            try { return await response.text(); } catch { return ""; }
        }
    }

    async function cancelConversion() {
        if (state.uploadRequest) {
            state.uploadRequest.abort();
            return;
        }
        if (!state.jobId || !state.job || TERMINAL_STATES.has(String(state.job.status).toLowerCase())) return;
        if (String(state.job.phase || "").toLowerCase() === "cancelling") return;
        const jobId = state.jobId;
        const generation = state.jobGeneration;
        elements.cancelButton.disabled = true;
        appendLog({ message: "正在请求终止转换…", level: "warning" });
        try {
            let response = await fetch(`${API}/${encodeURIComponent(jobId)}/cancel`, { method: "POST", headers: { Accept: "application/json" } });
            if (!isCurrentJob(jobId, generation)) return;
            if (response.status === 404 || response.status === 405) {
                response = await fetch(`${API}/${encodeURIComponent(jobId)}`, { method: "DELETE", headers: { Accept: "application/json" } });
                if (!isCurrentJob(jobId, generation)) return;
            }
            if (!response.ok) throw new Error((await readError(response)) || `终止失败（HTTP ${response.status}）`);
            const job = await response.json();
            if (!applyJobIfCurrent(job, "cancel", jobId, generation)) return;
            showToast("终止请求已提交。", "info");
        } catch (error) {
            if (!isCurrentJob(jobId, generation)) return;
            appendLog({ message: error.message || "终止请求失败。", level: "error" });
            showToast(error.message || "终止请求失败。", "error");
            updateActionState();
        }
    }

    async function finishJob(job, source, generation = state.jobGeneration) {
        if (!job || !state.jobId) return;
        const finishingId = state.jobId;
        if (!isCurrentJob(finishingId, generation)) return;
        closeEvents();
        updateActionState();
        updateDownloads(job);
        const status = String(job.status).toLowerCase();
        if (state.finishingJobId === finishingId) return;
        state.finishingJobId = finishingId;

        if (status === "succeeded") {
            setProgress(100, job.phase || "转换完成", false);
            appendLog({ message: job.message || "转换任务已完成。", level: "success" });
            showToast("转换完成，可以下载结果。", "success");
        } else if (status === "failed") {
            setProgress(job.progress || 0, job.phase || "转换失败", false);
            appendLog({ message: job.message || `转换任务失败${job.exitCode != null ? `，退出码 ${job.exitCode}` : ""}。`, level: "error" });
            showToast("转换失败，请查看日志与报告。", "error", 5000);
        } else {
            setProgress(job.progress || 0, job.phase || "转换已终止", false);
            appendLog({ message: job.message || "转换任务已终止。", level: "warning" });
        }

        // A final GET avoids racing the worker while it publishes logs/report artifacts.
        try {
            const refreshed = await fetchJob(finishingId);
            if (!isCurrentJob(finishingId, generation)) return;
            state.job = { ...job, ...refreshed };
            updateDownloads(state.job);
        } catch { /* The terminal snapshot is still useful. */ }

        if (!isCurrentJob(finishingId, generation)) return;
        if (state.job?.reportAvailable) await loadReport(finishingId, generation);
        else {
            elements.reportLoading.querySelector("p").textContent = status === "cancelled"
                ? "任务已终止，未生成转换报告。完整过程仍可从日志包下载。"
                : "此任务未生成可读取的转换报告，请下载日志查看原因。";
            elements.reportOverall.className = `status-badge ${status === "failed" ? "status-failed" : "status-cancelled"}`;
            elements.reportOverall.textContent = status === "failed" ? "无可用报告" : "任务已终止";
            elements.reportSummaryText.textContent = "未生成 report.json";
        }
        if (source === "restore") showToast("已恢复上次转换任务。", "info");
    }

    async function loadReport(jobId, generation = state.jobGeneration) {
        if (!isCurrentJob(jobId, generation)) return;
        try {
            const response = await fetch(`${API}/${encodeURIComponent(jobId)}/report`, { headers: { Accept: "application/json" } });
            if (!response.ok) throw new Error((await readError(response)) || `报告读取失败（HTTP ${response.status}）`);
            const report = await response.json();
            if (!isCurrentJob(jobId, generation)) return;
            renderReport(report);
        } catch (error) {
            if (!isCurrentJob(jobId, generation)) return;
            elements.reportLoading.hidden = false;
            elements.reportContent.hidden = true;
            elements.reportLoading.querySelector("p").textContent = error.message || "报告读取失败。";
            elements.reportOverall.className = "status-badge status-failed";
            elements.reportOverall.textContent = "报告读取失败";
            appendLog({ message: `报告读取失败：${error.message || "未知错误"}`, level: "error" });
        }
    }

    function normalizeReportResults(report) {
        const buckets = { converted: [], degraded: [], excluded: [], unsupported: [], failed: [] };
        const contentResults = Array.isArray(report.contentResults) ? report.contentResults : [];
        const fileResults = Array.isArray(report.fileResults) ? report.fileResults : [];

        contentResults.forEach((result) => {
            const disposition = String(result.disposition || "unsupported").toLowerCase();
            if (!buckets[disposition]) return;
            buckets[disposition].push({ ...result, recordType: "content" });
        });
        fileResults.forEach((result) => {
            let disposition = String(result.disposition || "unsupported").toLowerCase();
            if (disposition === "copied") disposition = "converted";
            if (!buckets[disposition]) return;
            buckets[disposition].push({ ...result, disposition, recordType: "file" });
        });
        return buckets;
    }

    function renderReport(report) {
        state.report = report;
        state.diagnosticMap = new Map();
        const diagnostics = Array.isArray(report.diagnostics) ? report.diagnostics : [];
        diagnostics.forEach((item, index) => {
            const code = String(item.code || `DIAGNOSTIC_${index + 1}`);
            if (!state.diagnosticMap.has(code)) state.diagnosticMap.set(code, []);
            state.diagnosticMap.get(code).push({ ...item, _index: index });
        });

        elements.reportLoading.hidden = true;
        elements.reportContent.hidden = false;
        setReportStatus(String(report.status || "").toLowerCase());

        const buckets = normalizeReportResults(report);
        const contentCount = Array.isArray(report.contentResults) ? report.contentResults.length : 0;
        const fileCount = Array.isArray(report.fileResults) ? report.fileResults.length : 0;
        elements.reportSummaryText.textContent = `${report.source?.name || "上传文件"} · ${report.target?.gameVersion ? `目标 ${report.target.gameVersion} · ` : ""}${contentCount} 个内容结果 / ${fileCount} 个文件结果`;

        for (const disposition of Object.keys(buckets)) {
            const items = buckets[disposition];
            const contentItems = items.filter((item) => item.recordType === "content").length;
            const fileItems = items.length - contentItems;
            const counter = $(`#count-${disposition}`);
            if (counter) {
                counter.textContent = String(items.length);
                counter.parentElement.querySelector("small").textContent = `${contentItems} 内容 · ${fileItems} 文件`;
                counter.parentElement.title = `${contentItems} 个声明级内容结果，${fileItems} 个文件级结果`;
            }
        }

        const status = String(report.status || "").toLowerCase();
        const summary = report.summary || {};
        const warningCount = Number(summary.warningCount || 0);
        const errorCount = Number(summary.errorCount || 0);
        elements.reportNotice.hidden = status === "success" && warningCount === 0 && errorCount === 0;
        elements.reportNotice.textContent = status === "success"
            ? `转换已完成，但报告包含 ${warningCount} 条警告和 ${errorCount} 条错误诊断，请按需核对。`
            : `这是尽力转换结果：${warningCount} 条警告，${errorCount} 条错误。请重点检查降级、暂不支持和失败项。`;

        renderValidation(report.validationStages || []);
        renderResultGroups(buckets);
        renderDiagnostics(diagnostics);
        renderInventory(report);
    }

    function renderValidation(stages) {
        elements.validationList.replaceChildren();
        elements.validationCount.textContent = `${stages.length} 项`;
        if (!stages.length) {
            elements.validationList.append(createElement("div", "empty-group", "未记录验证阶段。"));
            return;
        }
        const labels = { structure: "结构验证", runtime: "运行时验证", mapImport: "地图导入", serverLoad: "服务器加载" };
        stages.forEach((stage) => {
            const status = String(stage.status || "notRun");
            const item = createElement("div", "validation-item");
            item.append(createElement("strong", "", labels[stage.stage] || stage.stage || "未知阶段"));
            const badgeClass = status === "passed" ? "mini-passed" : status === "failed" ? "mini-failed" : "mini-notrun";
            const badgeLabel = status === "passed" ? "通过" : status === "failed" ? "失败" : "未执行";
            item.append(createElement("span", `mini-badge ${badgeClass}`, badgeLabel));
            item.append(createElement("p", "", stage.summary || "无补充说明"));
            elements.validationList.append(item);
        });
    }

    function renderResultGroups(buckets) {
        elements.resultGroups.replaceChildren();
        ["converted", "degraded", "excluded", "unsupported", "failed"].forEach((disposition) => {
            const items = buckets[disposition];
            const presentation = dispositionPresentation[disposition];
            const group = createElement("details", `report-group ${presentation.className}`);
            const summary = createElement("summary");
            const title = createElement("span", "group-title");
            const marker = createElement("i");
            marker.style.background = presentation.color;
            title.append(marker, document.createTextNode(presentation.label));
            summary.append(title, createElement("span", "group-count", `${items.length} 项`));
            group.append(summary);

            if (!items.length) {
                group.append(createElement("div", "empty-group", "此分类没有项目。"));
            } else {
                const wrapper = createElement("div", "result-table-wrap");
                const table = createElement("table", "result-table");
                table.innerHTML = "<colgroup><col style=\"width:29%\"><col style=\"width:29%\"><col style=\"width:42%\"></colgroup><thead><tr><th>来源 / 类型</th><th>输出</th><th>处理说明与诊断</th></tr></thead>";
                const tbody = createElement("tbody");
                table.append(tbody);
                wrapper.append(table);
                group.append(wrapper);

                let rendered = 0;
                const loadRow = createElement("div", "load-more-row");
                const loadButton = createElement("button", "load-more");
                loadButton.type = "button";
                loadRow.append(loadButton);
                group.append(loadRow);

                const renderNextBatch = () => {
                    const end = Math.min(rendered + REPORT_BATCH_SIZE, items.length);
                    const fragment = document.createDocumentFragment();
                    for (let index = rendered; index < end; index += 1) fragment.append(renderResultRow(items[index]));
                    tbody.append(fragment);
                    rendered = end;
                    const remaining = items.length - rendered;
                    loadRow.hidden = remaining === 0;
                    loadButton.textContent = remaining > 0 ? `继续显示（剩余 ${remaining} 项）` : "";
                };
                loadButton.addEventListener("click", renderNextBatch);
                group.addEventListener("toggle", () => {
                    if (group.open && rendered === 0) renderNextBatch();
                });
            }
            elements.resultGroups.append(group);
        });
    }

    function renderResultRow(result) {
        const row = createElement("tr");
        const sourceCell = createElement("td");
        const source = createElement("div", "result-name");
        const sourceName = result.recordType === "content" ? result.sourceSymbol : result.sourcePath;
        source.append(createElement("strong", "", sourceName || "未命名项目"));
        const sourceParts = [
            result.recordType === "content" ? "内容" : "文件",
            result.kind,
            result.sourceType,
            result.location?.line ? `${result.location.path || ""}:${result.location.line}` : result.location?.path,
        ].filter(Boolean);
        source.append(createElement("small", "", sourceParts.join(" · ")));
        sourceCell.append(source);

        const outputs = Array.isArray(result.outputPaths) && result.outputPaths.length
            ? result.outputPaths
            : [result.outputPath || result.outputName].filter(Boolean);
        const outputCell = createElement("td", "result-output", outputs.length ? outputs.join("\n") : "—");
        const reasonCell = createElement("td", "result-reason");
        reasonCell.append(createElement("span", "", result.reason || "未提供处理说明。"));
        const codes = Array.isArray(result.diagnosticCodes) ? result.diagnosticCodes : [];
        if (codes.length) {
            const links = createElement("div", "diagnostic-links");
            const messages = createElement("ul", "linked-diagnostic-messages");
            codes.forEach((code) => {
                const button = createElement("button", "diagnostic-link", code);
                button.type = "button";
                button.title = state.diagnosticMap.get(code)?.[0]?.message || "查看对应诊断";
                button.addEventListener("click", () => focusDiagnostic(code));
                links.append(button);
                const matches = state.diagnosticMap.get(code) || [];
                const message = matches.length
                    ? matches.map((item) => item.message).filter(Boolean).join(" / ")
                    : "报告中未找到此代码的详细消息。";
                const listItem = createElement("li");
                listItem.append(createElement("code", "", code), document.createTextNode(` — ${message}`));
                messages.append(listItem);
            });
            reasonCell.append(links, messages);
        }
        row.append(sourceCell, outputCell, reasonCell);
        return row;
    }

    function diagnosticDomId(code, index) {
        const safe = String(code).replace(/[^a-zA-Z0-9_-]/g, "-");
        return `diagnostic-${safe}-${index}`;
    }

    function renderDiagnostics(diagnostics) {
        elements.diagnosticsList.replaceChildren();
        elements.diagnosticsCount.textContent = `${diagnostics.length} 项`;
        if (!diagnostics.length) {
            elements.diagnosticsList.append(createElement("div", "empty-group", "没有诊断信息。"));
            return;
        }
        const fragment = document.createDocumentFragment();
        diagnostics.forEach((diagnostic, index) => {
            const severity = String(diagnostic.severity || "info").toLowerCase();
            const item = createElement("article", `diagnostic-item severity-${severity}`);
            item.id = diagnosticDomId(diagnostic.code || "unknown", index);
            item.dataset.search = [diagnostic.code, diagnostic.message, diagnostic.details, diagnostic.suggestion, diagnostic.location?.path, severity].filter(Boolean).join(" ").toLowerCase();
            item.append(createElement("span", "severity-mark"));
            const body = createElement("div");
            const head = createElement("div", "diagnostic-head");
            head.append(createElement("code", "", diagnostic.code || "UNKNOWN"));
            const meta = [severity.toUpperCase(), diagnostic.stage, diagnostic.status, diagnostic.location?.path].filter(Boolean).join(" · ");
            head.append(createElement("span", "", meta));
            body.append(head, createElement("p", "", diagnostic.message || "无诊断消息"));
            if (diagnostic.details) body.append(createElement("p", "diagnostic-details", diagnostic.details));
            if (diagnostic.suggestion) body.append(createElement("p", "diagnostic-suggestion", `建议：${diagnostic.suggestion}`));
            item.append(body);
            fragment.append(item);
        });
        elements.diagnosticsList.append(fragment);
    }

    function focusDiagnostic(code) {
        const details = elements.diagnosticsList.closest("details");
        details.open = true;
        const match = state.diagnosticMap.get(code)?.[0];
        if (!match) {
            elements.diagnosticSearch.value = code;
            filterDiagnostics(code);
            showToast(`报告中未找到 ${code} 的详细诊断。`, "info");
            return;
        }
        elements.diagnosticSearch.value = "";
        filterDiagnostics("");
        const target = document.getElementById(diagnosticDomId(code, match._index));
        if (!target) return;
        target.classList.remove("is-highlighted");
        void target.offsetWidth;
        target.classList.add("is-highlighted");
        target.scrollIntoView({ behavior: "smooth", block: "center" });
    }

    function filterDiagnostics(query) {
        const normalized = String(query || "").trim().toLowerCase();
        let visible = 0;
        elements.diagnosticsList.querySelectorAll(".diagnostic-item").forEach((item) => {
            const show = !normalized || item.dataset.search.includes(normalized);
            item.hidden = !show;
            if (show) visible += 1;
        });
        elements.diagnosticsCount.textContent = normalized ? `${visible} / ${state.report?.diagnostics?.length || 0} 项` : `${state.report?.diagnostics?.length || 0} 项`;
    }

    function renderInventory(report) {
        const inventory = report.inventory || {};
        const summary = report.summary || {};
        const stats = [
            ["扫描文件", summary.scannedFiles ?? inventory.scannedFiles ?? 0],
            ["内容清单", Array.isArray(inventory.contents) ? inventory.contents.length : summary.contentFiles || 0],
            ["资源清单", Array.isArray(inventory.assets) ? inventory.assets.length : summary.assetFiles || 0],
            ["忽略项目", Array.isArray(inventory.ignored) ? inventory.ignored.length : 0],
        ];
        elements.inventoryCount.textContent = `${stats[0][1]} 个文件`;
        elements.inventoryContent.replaceChildren();
        stats.forEach(([label, value]) => {
            const item = createElement("div", "inventory-stat");
            item.append(createElement("span", "", label), createElement("strong", "", value));
            elements.inventoryContent.append(item);
        });
    }

    async function copyTerminal() {
        const text = Array.from(elements.terminalOutput.querySelectorAll(".terminal-line"))
            .map((line) => Array.from(line.children).map((node) => node.textContent).join(" "))
            .join("\n");
        try {
            await navigator.clipboard.writeText(text);
            showToast("当前页面日志已复制。", "success");
        } catch {
            const area = document.createElement("textarea");
            area.value = text;
            area.style.position = "fixed";
            area.style.opacity = "0";
            document.body.append(area);
            area.select();
            const copied = document.execCommand("copy");
            area.remove();
            showToast(copied ? "当前页面日志已复制。" : "复制失败，请下载完整日志。", copied ? "success" : "error");
        }
    }

    async function checkHealth() {
        try {
            const response = await fetch("/api/health", { headers: { Accept: "application/json" }, cache: "no-store" });
            if (!response.ok) throw new Error();
            const health = await response.json();
            elements.serviceState.className = "service-state is-online";
            elements.serviceStateText.textContent = "转换服务在线";
            const maxUploadBytes = Number(health.maxUploadBytes);
            state.maxUploadBytes = Number.isFinite(maxUploadBytes) && maxUploadBytes > 0 ? maxUploadBytes : null;
            if (state.maxUploadBytes) {
                elements.fileHelp.textContent = `.zip / .jar / .hjson / .json / .json5 · 上限 ${formatBytes(state.maxUploadBytes)} · 目录请先压缩`;
                if (state.file && state.file.size > state.maxUploadBytes) {
                    const oversizedFile = state.file;
                    removeSelectedFile();
                    showUploadError(`所选文件为 ${formatBytes(oversizedFile.size)}，超过服务器上限 ${formatBytes(state.maxUploadBytes)}。`);
                }
            }
        } catch {
            elements.serviceState.className = "service-state is-offline";
            elements.serviceStateText.textContent = "转换服务不可用";
        }
    }

    async function restoreLastJob() {
        const savedId = readSavedJobId();
        if (!savedId) return;
        const generation = beginJobGeneration();
        closeEvents();
        state.job = null;
        state.jobId = savedId;
        elements.jobId.textContent = savedId;
        resetTerminal("正在恢复上次转换任务…");
        try {
            const job = await fetchJob(savedId);
            if (!applyJobIfCurrent(job, "restore", savedId, generation)) return;
            if (!TERMINAL_STATES.has(String(job.status).toLowerCase())) {
                appendLog({ message: `已重新连接任务 ${savedId}。`, level: "system" });
                connectEvents(savedId, generation);
            }
        } catch (error) {
            if (!isCurrentJob(savedId, generation)) return;
            saveJobId(null);
            state.jobId = null;
            elements.jobId.textContent = "—";
            resetTerminal("等待转换任务…");
        }
    }

    function bindEvents() {
        elements.dropZone.addEventListener("click", () => {
            if (!elements.fileInput.disabled) elements.fileInput.click();
        });
        elements.dropZone.addEventListener("keydown", (event) => {
            if ((event.key === "Enter" || event.key === " ") && !elements.fileInput.disabled) {
                event.preventDefault();
                elements.fileInput.click();
            }
        });
        elements.fileInput.addEventListener("change", () => selectFile(elements.fileInput.files?.[0]));
        ["dragenter", "dragover"].forEach((type) => elements.dropZone.addEventListener(type, (event) => {
            event.preventDefault();
            if (!elements.fileInput.disabled) elements.dropZone.classList.add("is-dragover");
        }));
        ["dragleave", "drop"].forEach((type) => elements.dropZone.addEventListener(type, (event) => {
            event.preventDefault();
            elements.dropZone.classList.remove("is-dragover");
        }));
        elements.dropZone.addEventListener("drop", (event) => selectFile(event.dataTransfer?.files?.[0]));
        window.addEventListener("dragover", (event) => event.preventDefault());
        window.addEventListener("drop", (event) => event.preventDefault());
        elements.removeFile.addEventListener("click", removeSelectedFile);
        elements.startButton.addEventListener("click", startConversion);
        elements.cancelButton.addEventListener("click", cancelConversion);
        elements.copyLog.addEventListener("click", copyTerminal);
        elements.diagnosticSearch.addEventListener("input", () => filterDiagnostics(elements.diagnosticSearch.value));
        [elements.downloadResult, elements.downloadLogs].forEach((anchor) => anchor.addEventListener("click", (event) => {
            if (anchor.getAttribute("aria-disabled") === "true") event.preventDefault();
        }));
    }

    async function initialize() {
        bindEvents();
        updateActionState();
        updateDownloads(null);
        checkHealth();
        state.servicePoll = window.setInterval(checkHealth, 30000);
        await restoreLastJob();
    }

    initialize();
})();
