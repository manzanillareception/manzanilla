// Generated from ui/reception/reception.js; run npm run build:reception.
(() => {
  "use strict";
  const native = !!window.ManzaniaNative;
  const hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const content = document.getElementById("content");
  const languages = { es: { name: "Spanish", native: "Español" }, en: { name: "English", native: "English" }, ca: { name: "Catalan", native: "Català" }, fr: { name: "French", native: "Français" }, de: { name: "German", native: "Deutsch" }, it: { name: "Italian", native: "Italiano" }, pt: { name: "Portuguese", native: "Português" }, nl: { name: "Dutch", native: "Nederlands" }, ar: { name: "Arabic", native: "العربية" }, zh: { name: "Chinese", native: "中文" }, ru: { name: "Russian", native: "Русский" }, uk: { name: "Ukrainian", native: "Українська" }, pl: { name: "Polish", native: "Polski" }, ro: { name: "Romanian", native: "Română" }, sv: { name: "Swedish", native: "Svenska" }, el: { name: "Greek", native: "Ελληνικά" }, tr: { name: "Turkish", native: "Türkçe" }, ja: { name: "Japanese", native: "日本語" }, ko: { name: "Korean", native: "한국어" }, hi: { name: "Hindi", native: "हिन्दी" }, cs: { name: "Czech", native: "Čeština" }, da: { name: "Danish", native: "Dansk" }, fi: { name: "Finnish", native: "Suomi" }, hu: { name: "Hungarian", native: "Magyar" }, bg: { name: "Bulgarian", native: "Български" }, hr: { name: "Croatian", native: "Hrvatski" }, sk: { name: "Slovak", native: "Slovenčina" }, sr: { name: "Serbian", native: "Српски" }, no: { name: "Norwegian", native: "Norsk" }, id: { name: "Indonesian", native: "Bahasa Indonesia" }, th: { name: "Thai", native: "ไทย" }, vi: { name: "Vietnamese", native: "Tiếng Việt" }, ms: { name: "Malay", native: "Bahasa Melayu" }, tl: { name: "Tagalog", native: "Tagalog" }, he: { name: "Hebrew", native: "עברית" }, fa: { name: "Persian", native: "فارسی" }, ur: { name: "Urdu", native: "اردو" }, bn: { name: "Bengali", native: "বাংলা" }, ta: { name: "Tamil", native: "தமிழ்" }, te: { name: "Telugu", native: "తెలుగు" }, kn: { name: "Kannada", native: "ಕನ್ನಡ" }, mr: { name: "Marathi", native: "मराठी" }, gu: { name: "Gujarati", native: "ગુજરાતી" }, pa: { name: "Punjabi", native: "ਪੰਜਾਬੀ" }, ne: { name: "Nepali", native: "नेपाली" }, kk: { name: "Kazakh", native: "Қазақша" }, ka: { name: "Georgian", native: "ქართული" }, hy: { name: "Armenian", native: "Հայերեն" }, et: { name: "Estonian", native: "Eesti" }, lv: { name: "Latvian", native: "Latviešu" }, lt: { name: "Lithuanian", native: "Lietuvių" }, af: { name: "Afrikaans", native: "Afrikaans" } };
  const translationLanguages = languages;
  let languageSide = "language";
  let appearance = "dark";
  const themeIcon = () => appearance === "light" ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M20 15.7A8.5 8.5 0 0 1 8.3 4 8.5 8.5 0 1 0 20 15.7Z"/></svg>' : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg>';
  const copy = {
    en: { welcome: "How can I help?", reception: "Hotel assistance", ready: "Ready when you are.", start: "Start conversation", listen: "Listening", speaking: "Speaking", connect: "Connecting", end: "End conversation", translate: "Live translation", quiet: "The microphone stays off until you start.", draft: "Save this message?", save: "Save message", cancel: "Cancel", saved: "Saved for reception", local: "Saved on this computer. Not sent to a staff member.", message: "Leave a message", operator: "Contact reception", noOperator: "An operator connection is not set up. Please use the hotel’s posted contact number.", request: "Ask about your stay, arrival instructions, or a message for reception.", begin: "Speak. We translate both ways." },
    es: { welcome: "¿En qué puedo ayudarte?", reception: "Asistencia del hotel", ready: "Todo listo.", start: "Iniciar conversación", listen: "Escuchando", speaking: "Hablando", connect: "Conectando", end: "Finalizar", translate: "Traducción en directo", quiet: "El micrófono permanece apagado hasta que comiences.", draft: "¿Guardar este mensaje?", save: "Guardar mensaje", cancel: "Cancelar", saved: "Guardado para recepción", local: "Guardado en este ordenador. No enviado al personal.", message: "Dejar un mensaje", operator: "Contactar con recepción", noOperator: "No hay conexión con un operador. Usa el número de contacto indicado por el hotel.", request: "Pregunta sobre tu estancia, la llegada o deja un mensaje para recepción.", begin: "Habla con naturalidad y espera tu turno." },
    ja: { welcome: "お手伝いします", reception: "ホテルのご案内", ready: "準備ができました", start: "会話を開始", listen: "お話を伺っています", speaking: "話しています", connect: "接続中", end: "会話を終了", translate: "リアルタイム通訳", quiet: "開始するまでマイクはオフです。", draft: "このメッセージを保存しますか？", save: "保存する", cancel: "キャンセル", saved: "受付へのメッセージを保存しました", local: "このパソコンに保存しました。スタッフにはまだ送信されていません。", message: "メッセージを残す", operator: "受付に連絡", noOperator: "オペレーターへの接続は未設定です。ホテルに掲示された連絡先をご利用ください。", request: "ご滞在や到着についてのご質問、または受付へのメッセージをどうぞ。", begin: "受付の方と交互にお話しください。" }
  };
  if (native) {
    copy.en.local = "Saved on this device. Not sent to a staff member.";
    copy.es.local = "Guardado en este dispositivo. No enviado al personal.";
    copy.ja.local = "この端末に保存しました。スタッフにはまだ送信されていません。";
  }
  Object.assign(copy, {
    zh: { ...copy.en, welcome: "有什么可以帮您？", ready: "准备好了", start: "开始对话", listen: "正在聆听", speaking: "正在说话", connect: "正在连接", end: "结束对话", draft: "保存这条留言？", save: "保存留言", cancel: "取消", message: "留下留言", operator: "联系前台" },
    fr: { ...copy.en, welcome: "Comment puis-je vous aider ?", ready: "À votre écoute.", start: "Commencer", listen: "Je vous écoute", speaking: "Je vous réponds", connect: "Connexion", end: "Terminer", draft: "Enregistrer ce message ?", save: "Enregistrer", cancel: "Annuler", message: "Laisser un message", operator: "Contacter la réception" },
    de: { ...copy.en, welcome: "Wie kann ich helfen?", ready: "Bereit für Sie.", start: "Gespräch starten", listen: "Ich höre zu", speaking: "Ich antworte", connect: "Verbinden", end: "Beenden", draft: "Nachricht speichern?", save: "Speichern", cancel: "Abbrechen", message: "Nachricht hinterlassen", operator: "Rezeption kontaktieren" },
    it: { ...copy.en, welcome: "Come posso aiutarti?", ready: "Sono qui per te.", start: "Inizia", listen: "Ti ascolto", speaking: "Sto rispondendo", connect: "Connessione", end: "Termina", draft: "Salvare il messaggio?", save: "Salva", cancel: "Annulla", message: "Lascia un messaggio", operator: "Contatta la reception" },
    pt: { ...copy.en, welcome: "Como posso ajudar?", ready: "Tudo pronto.", start: "Começar", listen: "Estou a ouvir", speaking: "A responder", connect: "A ligar", end: "Terminar", draft: "Guardar a mensagem?", save: "Guardar", cancel: "Cancelar", message: "Deixar mensagem", operator: "Contactar a receção" }
  });
  const confirmationCopy = {
    en: { prepared: "Review your message.", notSent: "Not sent.", continue: "Continue", saving: "Saving…" },
    es: { prepared: "Revisa tu mensaje.", notSent: "No enviado.", continue: "Continuar", saving: "Guardando…" },
    ja: { prepared: "メッセージを確認", notSent: "未送信", continue: "続ける", saving: "保存中…" },
    zh: { prepared: "确认留言", notSent: "未发送", continue: "继续", saving: "保存中…" },
    fr: { prepared: "Vérifiez votre message.", notSent: "Non envoyé.", continue: "Continuer", saving: "Enregistrement…" },
    de: { prepared: "Nachricht prüfen.", notSent: "Nicht gesendet.", continue: "Weiter", saving: "Speichern…" },
    it: { prepared: "Controlla il messaggio.", notSent: "Non inviato.", continue: "Continua", saving: "Salvataggio…" },
    pt: { prepared: "Reveja a mensagem.", notSent: "Não enviado.", continue: "Continuar", saving: "A guardar…" }
  };
  Object.entries(confirmationCopy).forEach(([language, words]) => Object.assign(copy[language], words));
  const conciergeCopy = {
    en: ["Check in", "Talk to a person", "Get a taxi", "Ask anything"],
    es: ["Llegada al hotel", "Hablar con alguien", "Pedir un taxi", "Hacer una pregunta"],
    ja: ["チェックイン", "スタッフと話す", "タクシーを呼ぶ", "質問する"],
    zh: ["办理入住", "联系工作人员", "叫出租车", "咨询问题"],
    fr: ["Arrivée à l’hôtel", "Parler à quelqu’un", "Demander un taxi", "Poser une question"],
    de: ["Einchecken", "Mit jemandem sprechen", "Taxi bestellen", "Eine Frage stellen"],
    it: ["Check-in", "Parla con una persona", "Chiedi un taxi", "Fai una domanda"],
    pt: ["Fazer check-in", "Falar com uma pessoa", "Pedir um táxi", "Fazer uma pergunta"]
  };
  const shortcuts = ["checkin", "operator", "taxi", "question"];
  const concierge = () => conciergeCopy[state.language] || conciergeCopy.en;
  const greet = (language) => window.MANZANILLA_GREET && window.MANZANILLA_GREET[language] || "";
  const localeLanguage = (navigator.language || "").slice(0, 2).toLowerCase();
  const firstLanguage = Object.keys(languages)[0];
  const initialGuest = hasOwn(languages, localeLanguage) ? localeLanguage : firstLanguage;
  const state = { page: "idle", mode: "translation", night: false, language: initialGuest, staff_language: initialGuest, demo: !native, connected: false, audioAvailable: false, receptionAvailable: false, openaiConfigured: false, audioGate: "Add an OpenAI API key for live translation.", receptionGate: "Checking night receptionist setup…", audio: "off", active: false, phase: "", sessionId: "", generation: null, closed: true, terminated: true, error: "", metrics: "", utterances: /* @__PURE__ */ new Map(), draft: null, confirmed: false, operation: null, outcome: null, notices: [], hotel: "", pendingPage: "", micLevel: 0, liveText: "", assistantDraft: "", localSource: null };
  let queuedShortcut = "";
  const captionNodes = /* @__PURE__ */ new Map();
  const MAX_TRANSCRIPT = 12e3;
  const selected = { language: false, staff_language: false };
  let defaultReceived = false;
  let preferencesRestored = false;
  const icons = {
    arrow: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5"/></svg>',
    translate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 5h12M9 2v3m3 0c-.5 5-3.5 8-8 10m1-8c1.5 3 4.2 5.7 7 7m1 7 4-11 4 11m-6.4-4h4.8"/></svg>',
    bell: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M3 17h18M5 17v-3a7 7 0 0 1 14 0v3M2 21h20M12 4v3m-2-3h4"/></svg>',
    back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m14 6-6 6 6 6"/></svg>',
    bed: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 18v3m18-3v3M3 11V5h18v6M2 18v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5H2Zm4-7V8h5v3m2 0V8h5v3"/></svg>',
    car: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m4 9 2-5h12l2 5M3 18v3m18-3v3M2 18v-6l2-3h16l2 3v6H2Zm3-5h2m10 0h2"/></svg>',
    message: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 3h18v14H8l-5 4V3Zm4 5h10M7 12h7"/></svg>',
    mic: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/></svg>',
    micOff: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8M3 3l18 18"/></svg>'
  };
  const esc = (value) => String(value != null ? value : "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const t = () => copy[state.language] || copy.en;
  const langName = (language) => {
    var _a, _b;
    return ((_a = translationLanguages[language]) == null ? void 0 : _a.native) || ((_b = languages[language]) == null ? void 0 : _b.native) || language;
  };
  const languagePicker = (side, label) => '<section class="language-side" data-keypad="'.concat(languageSide === side, '"><label for="').concat(side, '">').concat(label, '</label><select id="').concat(side, '" data-language-side="').concat(side, '" lang="').concat(state[side], '" dir="').concat(languageDir(state[side]), '" aria-describedby="').concat(side, '-hint" ').concat(state.audio !== "off" ? "disabled" : "", ">").concat(Object.entries(translationLanguages).map(([id, item]) => '<option value="'.concat(id, '" lang="').concat(id, '" dir="').concat(languageDir(id), '" ').concat(state[side] === id ? "selected" : "", ">").concat(esc(item.native), "</option>")).join(""), '</select><small id="').concat(side, '-hint">').concat(side === "language" ? "First conversation language" : "Second conversation language", "</small></section>");
  const send = (action2, detail = {}) => {
    if (!native) return;
    const scope = state.sessionId && !["start", "info", "languages", "appearance", "night", "settings", "voice_setup", "night_setup", "request_location", "apply_update", "check_updates"].includes(action2) ? { session_id: state.sessionId, ...state.generation === null ? {} : { generation: state.generation } } : {};
    window.ManzaniaNative.postMessage(JSON.stringify({ action: action2, ...scope, ...detail }));
  };
  const action = (name, label, className = "primary", disabled = false) => '<button type="button" class="'.concat(className, '" data-action="').concat(name, '" ').concat(disabled ? "disabled" : "", ">").concat(label, "</button>");
  const fine = (text) => '<p class="fine">'.concat(esc(text), "</p>");
  const operationField = (field, index) => {
    const limit = Number.isInteger(field.max_length) && field.max_length > 0 ? field.max_length : { original_request: 4e3, guest_name: 100, room_number: 20, destination: 300, pickup_time: 100, query: 300 }[field.name] || 300;
    const attrs = 'id="operation-field-'.concat(index, '" name="').concat(esc(field.name), '" maxlength="').concat(limit, '" ').concat(field.required ? "required" : "", " ").concat(state.operation.pending ? "disabled" : "", ' autocomplete="off" dir="auto"');
    const value = esc(state.operation.values[field.name] || "");
    return '<label for="operation-field-'.concat(index, '">').concat(esc(field.label)).concat(field.required ? " *" : "").concat(field.name === "original_request" ? "<textarea ".concat(attrs, ">").concat(value, "</textarea>") : "<input ".concat(attrs, ' value="').concat(value, '">'), "</label>");
  };
  const languageDir = (language) => !language ? "auto" : /^(ar|he|fa|ur)(-|$)/.test(language) ? "rtl" : "ltr";
  const captionSpeaker = (entry) => entry.side === "guest" ? "Guest" : entry.side === "staff" ? "Receptionist" : "speaker unknown";
  const captionParts = (entry) => ["original", "text"].filter((field) => entry[field] !== "").map((field) => {
    const output = entry.side === "assistant" ? "Assistant output" : field === "original" ? "Original" : entry.association === "unlinked" ? "Provider output" : "Translation";
    const speaker = entry.side === "assistant" ? "" : " · ".concat(captionSpeaker(entry)).concat(entry.speakerAttribution === "selected" ? " (selected)" : "");
    const language = entry[field === "original" ? "from" : "to"];
    return { field, language, text: entry[field], label: "".concat(output).concat(speaker).concat(entry.association === "unlinked" ? " · unlinked" : "").concat(language ? " · ".concat(langName(language)) : "") };
  });
  const captionClass = (entry) => "caption caption-".concat(entry.side === "guest" || entry.side === "human" ? "guest" : "reception");
  const captionBody = (entry) => captionParts(entry).map((part, index) => '<div class="caption-part'.concat(index ? " caption-divider" : "", '" data-field="').concat(part.field, '" data-final="').concat(entry.final[part.field], '"><span class="caption-language">').concat(esc(part.label), '</span><p class="').concat(state.mode === "translation" ? part.field === "original" ? "said" : "translated" : "caption-".concat(part.field), '" lang="').concat(esc(part.language || "und"), '" dir="').concat(languageDir(part.language), '">').concat(esc(part.text), "</p></div>")).join("");
  const caption = (entry) => '<article class="'.concat(captionClass(entry), '" data-caption-key="').concat(esc(entry.id), '" aria-atomic="true">').concat(captionBody(entry), "</article>");
  function localSourceUrl(value) {
    if (typeof value !== "string" || value.length > 2048) return "";
    try {
      const url = new URL(value);
      return url.protocol === "https:" && ["openstreetmap.org", "www.openstreetmap.org"].includes(url.hostname) && !url.username && !url.password && !url.port ? url.href : "";
    } catch (e) {
      return "";
    }
  }
  const sourceHtml = () => '<p class="local-source" '.concat(state.localSource ? "" : "hidden", ">").concat(state.localSource ? "<span>".concat(esc(state.localSource.attribution), '</span> <a href="').concat(esc(state.localSource.url), '" rel="noopener noreferrer" target="_blank">OpenStreetMap source</a>') : "", "</p>");
  function receiveLocalSource(event) {
    const url = localSourceUrl(event.source_url);
    if (!url || typeof event.attribution !== "string" || !event.attribution.trim()) return;
    state.localSource = { attribution: event.attribution.slice(0, 200), url };
    const target = content.querySelector(".local-source");
    if (!target) return;
    target.hidden = false;
    target.textContent = "";
    const attribution = document.createElement("span");
    attribution.textContent = state.localSource.attribution;
    const link = document.createElement("a");
    link.href = state.localSource.url;
    link.textContent = "OpenStreetMap source";
    link.rel = "noopener noreferrer";
    link.target = "_blank";
    target.appendChild(attribution);
    target.appendChild(document.createTextNode(" "));
    target.appendChild(link);
  }
  const streamText = () => [state.liveText, state.assistantDraft].filter(Boolean).join("\n\n").slice(-MAX_TRANSCRIPT);
  const streamHtml = () => '<article class="live-transcript" aria-atomic="true" '.concat(streamText() ? "" : "hidden", '><p class="live-transcript-text" dir="auto">').concat(esc(streamText()), "</p></article>");
  function receiveStream(event) {
    var _a;
    if (typeof event.text !== "string") return;
    if (event.type === "translation_text") {
      state.liveText = (state.liveText + event.text).slice(-MAX_TRANSCRIPT);
    } else if (event.final === true) {
      state.assistantDraft = event.text.slice(-MAX_TRANSCRIPT);
      state.liveText = streamText();
      state.assistantDraft = "";
    } else {
      state.assistantDraft = (state.assistantDraft + event.text).slice(-MAX_TRANSCRIPT);
    }
    const transcript = content.querySelector(".conversation");
    const paragraph = transcript == null ? void 0 : transcript.querySelector(".live-transcript-text");
    if (!paragraph) return;
    const scroller = transcript.closest(".session-scroll");
    const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
    paragraph.textContent = streamText();
    paragraph.parentElement.hidden = !paragraph.textContent;
    if (paragraph.textContent) (_a = transcript.querySelector(".empty-transcript")) == null ? void 0 : _a.remove();
    if (follow) scroller.scrollTop = scroller.scrollHeight;
  }
  const conversation = () => {
    const hint = state.mode === "translation" ? "Speak naturally. Translation appears here." : "Ask the night receptionist about your stay or nearby places.";
    return streamHtml() + (state.mode === "reception" ? sourceHtml() : "") + (!streamText() && !state.utterances.size ? '<div class="empty-transcript">'.concat(esc(hint), "</div>") : "") + (state.mode === "reception" ? [...state.utterances.values()].map(caption).join("") : "");
  };
  function updateCaption(entry) {
    var _a;
    const transcript = content.querySelector(".conversation");
    if (!transcript) return;
    const scroller = transcript.closest(".session-scroll");
    const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
    let node = captionNodes.get(entry.id);
    if (!node) {
      (_a = transcript.querySelector(".empty-transcript")) == null ? void 0 : _a.remove();
      node = document.createElement("article");
      node.className = captionClass(entry);
      node.dataset.captionKey = entry.id;
      node.setAttribute("aria-atomic", "true");
      transcript.appendChild(node);
      captionNodes.set(entry.id, node);
    }
    node.innerHTML = captionBody(entry);
    if (follow) scroller.scrollTop = scroller.scrollHeight;
  }
  const phaseLabel = () => ({ connecting: t().connect, reconnecting: "Reconnecting…", ready: "Night receptionist ready", greeting: "Welcome", thinking: state.mode === "translation" ? "Translating" : "Thinking", speaking: t().speaking, listening: "Live", ending: "Stopping", stopped: "Stopped" })[state.phase] || t().reception;
  const sessionAudioText = () => ({ off: "Mic off", recording: "Mic on", muted: "Mic muted", stopping: "Releasing mic", unknown: "Mic state unknown · ask staff" })[state.audio];
  const voiceBall = (className = "") => {
    const bands = state.micLevel > 0 ? state.bands || [state.micLevel, state.micLevel, state.micLevel] : [0, 0, 0];
    const muted = state.audio === "muted";
    const label = muted ? "Unmute the microphone" : "Mute the microphone";
    const enabled = state.active && !state.closed && ["recording", "muted"].includes(state.audio);
    return '<button type="button" class="voice-ball '.concat(className, '" data-action="mute" data-hearing="').concat(state.audio === "recording", '" aria-pressed="').concat(muted, '" aria-label="').concat(label, '" ').concat(enabled ? "" : "disabled", ' style="--level:').concat(state.micLevel, ";--b0:").concat(bands[0], ";--b1:").concat(bands[1], ";--b2:").concat(bands[2], '">').concat(muted ? icons.micOff : icons.mic, "</button>");
  };
  const sessionDock = () => {
    if (state.mode === "translation") {
      if (state.terminated) {
        const safe = !state.active && state.audio === "off";
        return '<div class="session-actions" data-stopped="true"><div class="session-control"><div class="session-control-copy"><strong>Conversation stopped</strong><span>'.concat(esc(sessionAudioText()), "</span></div></div>").concat(action("restart", "Try again", "primary", !safe), "</div>");
      }
      return voiceBall();
    }
    const stopped = state.closed || state.terminated;
    const restart = state.terminated && !state.active && state.audio === "off";
    const indicator = stopped ? '<div class="session-icon" role="img" aria-label="Night receptionist">'.concat(icons.bell, "</div>") : voiceBall("reception-mic");
    return '<div class="session-actions" data-stopped="'.concat(stopped, '"><div class="session-control">').concat(indicator, '<div class="session-control-copy"><strong>').concat(esc(stopped ? state.phase === "ending" ? "Stopping…" : "Stopped" : phaseLabel()), "</strong><span>").concat(esc(sessionAudioText()), "</span></div></div>").concat(!stopped ? action("message", "Message staff", "secondary", ["connecting", "reconnecting", "greeting"].includes(state.phase)) : "").concat(action(restart ? "restart" : "stop", restart ? "Restart" : stopped ? "Releasing…" : "Stop", "stop", stopped && !restart), "</div>");
  };
  function syncMic() {
    const button = content.querySelector(".voice-ball");
    if (!button) return;
    const muted = state.audio === "muted";
    button.disabled = !state.active || state.closed || !["recording", "muted"].includes(state.audio);
    button.dataset.hearing = String(state.audio === "recording");
    button.setAttribute("aria-pressed", String(muted));
    button.setAttribute("aria-label", muted ? "Unmute the microphone" : "Mute the microphone");
    button.innerHTML = muted ? icons.micOff : icons.mic;
  }
  function renderNotices() {
    const target = document.getElementById("notices");
    target.hidden = !state.notices.length;
    target.innerHTML = state.notices.map((notice, index) => '<div class="notice'.concat(["error", "failed", "unavailable", "denied"].includes(notice.status) ? " error" : "", '"><span><strong>Service update</strong> ').concat(esc(notice.text), '</span><button data-dismiss="').concat(index, '" aria-label="Dismiss service update">×</button></div>')).join("");
  }
  function addNotice(text, status = "") {
    if (!text) return;
    state.notices.push({ text: String(text), status: String(status) });
    renderNotices();
  }
  function render() {
    var _a, _b, _c, _d, _e;
    document.documentElement.dataset.appearance = appearance;
    document.documentElement.dataset.translation = state.page === "active" && state.mode === "translation" ? "live" : "";
    document.documentElement.lang = ["idle", "languages", "translation_languages"].includes(state.page) ? "en" : state.language;
    document.documentElement.dataset.night = String(state.night);
    document.documentElement.dataset.page = state.page;
    document.documentElement.dataset.captureActive = String(state.active);
    const nightToggle = document.getElementById("night-toggle");
    nightToggle.textContent = state.night ? "After-hours on" : "After-hours reception";
    nightToggle.setAttribute("aria-label", state.night ? "Turn off after-hours reception" : "Turn on after-hours reception");
    nightToggle.setAttribute("aria-pressed", String(state.night));
    nightToggle.hidden = state.page !== "idle";
    document.getElementById("settings").hidden = !native || !["idle", "error", "translation_languages"].includes(state.page);
    document.getElementById("settings").setAttribute("aria-label", state.mode === "translation" ? "OpenAI translation key" : "Night receptionist setup");
    document.getElementById("settings").title = state.mode === "translation" ? "OpenAI translation key" : "Night receptionist setup";
    document.getElementById("check-updates").hidden = !native || state.page !== "idle";
    content.dataset.page = state.page;
    document.getElementById("mode-title").textContent = state.night ? "After-hours reception" : "Reception";
    document.getElementById("connection-text").textContent = state.receptionAvailable ? "Night receptionist ready" : state.connected ? "Hotel connected" : "Night setup needed";
    document.getElementById("hotel-label").textContent = state.hotel || "Hotel reception";
    document.getElementById("privacy").innerHTML = '<span class="mic-dot'.concat(state.audio === "recording" ? " mic-live" : "", '"></span>').concat(esc(sessionAudioText()));
    document.getElementById("hardware-hint").textContent = state.active ? "Green or red to stop" : state.page === "translation_languages" ? "Digits: first ten · " + (state.audioAvailable ? "Green: start · " : "Audio unavailable · ") + "Red: back" : state.night ? "Green: hotel assistance" : "Green: translation";
    const homeAction = (name, icon, label, note = "") => '<button class="home-action'.concat(name === (state.night ? "reception" : "translation") ? " home-action-main" : "", '" data-action="').concat(name, '">').concat(name === (state.night ? "reception" : "translation") ? '<span class="orb">' + icons.arrow + "</span>" : icon, "<span>").concat(label, "</span>").concat(note ? "<small>" + note + "</small>" : "", "</button>");
    const html = {
      idle: () => '<div class="welcome"><h1>Welcome.</h1><p>'.concat(state.night ? "Self-service hotel assistance." : "Live translation and a night receptionist.", '</p></div><nav class="home-actions" aria-label="Reception services">').concat(state.night ? homeAction("reception", icons.bell, "Night receptionist", "Ask about your stay") + homeAction("translation", icons.translate, "Live translation", state.audioAvailable ? "Talk to each other" : "Audio unavailable") : homeAction("translation", icons.translate, "Live translation", state.audioAvailable ? "Talk to each other" : "Audio unavailable") + homeAction("reception", icons.bell, "Night receptionist")).concat(homeAction("request:checkin", icons.bed, "Arrival guide")).concat(homeAction("request:taxi", icons.car, "Taxi request")).concat(homeAction("request:message", icons.message, "Message staff"), '</nav><div class="appearance-row"><button class="appearance-toggle" data-action="appearance" aria-label="Switch to ').concat(appearance === "dark" ? "day" : "night", ' mode" title="Day / night mode · keypad 1">').concat(themeIcon(), "</button></div>"),
      languages: () => '<div class="status-line"><div><h2 class="language-heading">Night receptionist</h2></div><button class="back" data-action="back">'.concat(icons.back, 'Back</button></div><label class="assist-label" for="assist-language">Language</label><select id="assist-language" class="assist-language" lang="').concat(state.language, '" dir="').concat(languageDir(state.language), '">').concat(Object.entries(languages).map(([id, item]) => '<option value="'.concat(id, '" lang="').concat(id, '" dir="').concat(languageDir(id), '" ').concat(state.language === id ? "selected" : "", ">").concat(esc(item.native), "</option>")).join(""), '</select><p class="setup-feedback" role="status">').concat(esc(state.receptionAvailable ? "Ready. Start to talk with the night receptionist." : state.receptionGate), "</p>").concat(action("assist-go", "Start night receptionist", "primary assist-go", !state.receptionAvailable || state.audio !== "off"), '<div class="button-row setup-options">').concat(action("night_setup", "Hotel and nearby places", "secondary")).concat(action("request_location", "Use device location", "secondary"), "</div>"),
      translation_languages: () => '<div class="status-line"><div><h2 class="language-heading">Choose languages</h2><p class="keypad-hint">One shared conversation</p></div><button class="back" data-action="back">'.concat(icons.back, 'Back</button></div><div class="language-pair">').concat(languagePicker("language", "Language 1")).concat(languagePicker("staff_language", "Language 2"), '</div><p id="translation-readiness" class="setup-feedback" role="status">').concat(esc(translationReadiness()), '</p><button type="button" class="primary setup-start" data-action="start" aria-describedby="translation-readiness" ').concat(!state.audioAvailable || state.audio !== "off" || sameLanguage() ? "disabled" : "", ">Start translation</button>").concat(!state.openaiConfigured ? action("setup-translation", "Add OpenAI API key", "secondary setup-key", state.audio !== "off") : ""),
      active: () => state.mode === "translation" ? '<button class="translate-back" data-action="back" aria-label="Back">'.concat(icons.back, '</button><p class="pair-title">').concat(esc(langName(state.language)), " ↔ ").concat(esc(langName(state.staff_language)), '</p><p class="translation-status" role="status" ').concat(state.phase === "reconnecting" ? "" : "hidden", ">").concat(esc(phaseLabel()), '</p><div class="session-scroll"><div class="conversation" role="log" aria-label="Conversation transcript" aria-live="polite">').concat(conversation(), "</div></div>").concat(sessionDock()) : '<div class="status-line session-top"><div><h2>Night receptionist</h2><p>'.concat(esc(phaseLabel()), '</p></div><button class="back" data-action="back">').concat(icons.back, "Back</button></div>").concat(state.error ? '<div class="notice error">'.concat(esc(state.error), "</div>") : "", '<div class="session-scroll">').concat(!state.closed ? '<div class="shortcut-list">'.concat(shortcuts.map((id, i) => action(id, '<span class="key">'.concat(i + 1, "</span><strong>").concat(esc(concierge()[i]), "</strong>"), "choice shortcut", ["connecting", "reconnecting", "greeting"].includes(state.phase))).join(""), "</div>") : "", '<div class="conversation" role="log" aria-label="Conversation transcript" aria-live="polite">').concat(conversation(), "</div>").concat(state.metrics ? '<p class="metrics">'.concat(esc(state.metrics), "</p>") : "", "</div>").concat(sessionDock()),
      operation_form: () => {
        var _a2, _b2, _c2, _d2, _e2, _f, _g;
        return '<div class="status-line"><h2>'.concat(esc(((_a2 = state.operation) == null ? void 0 : _a2.action) === "message" ? "Message staff" : concierge()[shortcuts.indexOf((_b2 = state.operation) == null ? void 0 : _b2.action)] || "Hotel request"), '</h2><button class="back" data-action="back">').concat(icons.back, 'Back</button></div><form id="operation-form" class="operation-form" aria-busy="').concat(!!((_c2 = state.operation) == null ? void 0 : _c2.pending), '">').concat((((_d2 = state.operation) == null ? void 0 : _d2.fields) || []).map(operationField).join(""), '<div class="button-row"><button type="submit" class="primary" ').concat(((_e2 = state.operation) == null ? void 0 : _e2.pending) ? "disabled" : "", ">").concat(((_f = state.operation) == null ? void 0 : _f.pending) ? "Reviewing…" : "Review", "</button>").concat(action("cancel-operation", esc(t().cancel), "secondary", (_g = state.operation) == null ? void 0 : _g.pending), "</div></form>").concat(action("stop", "End", "stop"));
      },
      confirming: () => {
        var _a2, _b2, _c2, _d2, _e2, _f;
        return '<div class="status-line"><h2>Confirm request?</h2><button class="back" data-action="back">'.concat(icons.back, 'Back</button></div><div class="draft" dir="auto">').concat(esc(((_a2 = state.draft) == null ? void 0 : _a2.message) || ""), "</div>").concat(fine(((_b2 = state.draft) == null ? void 0 : _b2.attempted) ? state.draft.approved === false ? "No cancellation receipt yet. Retry to check the result." : "No receipt yet. Retry the same request to check its result." : "Confirm only if the details are correct."), '<div class="button-row">').concat(action(((_c2 = state.draft) == null ? void 0 : _c2.attempted) ? "retry-draft" : "confirm", state.confirmed ? ((_d2 = state.draft) == null ? void 0 : _d2.approved) === false ? "Cancelling…" : "Sending…" : ((_e2 = state.draft) == null ? void 0 : _e2.attempted) ? "Retry" : "Confirm", "primary", state.confirmed)).concat(state.operation ? action("edit-draft", "Edit", "secondary", state.confirmed || ((_f = state.draft) == null ? void 0 : _f.attempted)) : "").concat(action("cancel-draft", esc(t().cancel), "secondary", state.confirmed), "</div>");
      },
      saved: () => {
        var _a2, _b2;
        return '<h2>Request receipt</h2><section class="request-receipt"><p class="lead" dir="auto">'.concat(esc(((_a2 = state.outcome) == null ? void 0 : _a2.text) || "No receipt returned. Ask staff to check."), "</p>").concat(((_b2 = state.outcome) == null ? void 0 : _b2.receipt) ? '<div class="receipt" dir="auto">'.concat(esc(state.outcome.receipt), "</div>") : "", '</section><div class="button-row">').concat(action("continue", esc(t().continue))).concat(action("stop", "End", "secondary"), "</div>");
      },
      error: () => '<div class="status-line"><h2>Conversation unavailable</h2><button class="back" data-action="back">'.concat(icons.back, 'Back</button></div><div class="notice error">').concat(esc(state.error || "Please ask reception for help."), "</div>").concat(fine(state.audio === "off" ? "Microphone off. Staff can check this device’s setup." : "Audio release is not confirmed. Ask reception for help before restarting."), '<div class="button-row">').concat(action("retry", "Try again", "primary", state.audio !== "off")).concat(action("home", "Back to home", "secondary"), "</div>")
    };
    const focusedElement = document.activeElement;
    const focusedAction = (_a = focusedElement == null ? void 0 : focusedElement.dataset) == null ? void 0 : _a.action;
    const focusedSelect = (_b = focusedElement == null ? void 0 : focusedElement.dataset) == null ? void 0 : _b.languageSide;
    const focusedAssist = (focusedElement == null ? void 0 : focusedElement.id) === "assist-language";
    const focusedInput = (focusedElement == null ? void 0 : focusedElement.closest("#operation-form")) ? { name: focusedElement.name, start: focusedElement.selectionStart, end: focusedElement.selectionEnd } : null;
    const scrollTop = content.scrollTop;
    const scrolls = [...content.querySelectorAll(".session-scroll,.draft,.language-list")].map((element) => ({ top: element.scrollTop, follow: element.classList.contains("session-scroll") && element.scrollHeight - element.clientHeight - element.scrollTop < 60 }));
    captionNodes.clear();
    content.innerHTML = (html[state.page] || html.idle)();
    content.querySelectorAll("[data-caption-key]").forEach((node) => captionNodes.set(node.dataset.captionKey, node));
    content.scrollTop = scrollTop;
    [...content.querySelectorAll(".session-scroll,.draft,.language-list")].forEach((element, index) => {
      const previous = scrolls[index];
      element.scrollTop = (previous == null ? void 0 : previous.follow) ? element.scrollHeight : (previous == null ? void 0 : previous.top) || 0;
    });
    if (focusedAction) (_c = [...content.querySelectorAll("[data-action]")].find((button) => button.dataset.action === focusedAction)) == null ? void 0 : _c.focus({ preventScroll: true });
    if (focusedSelect) (_d = content.querySelector('[data-language-side="'.concat(focusedSelect, '"]'))) == null ? void 0 : _d.focus({ preventScroll: true });
    if (focusedAssist) (_e = content.querySelector("#assist-language")) == null ? void 0 : _e.focus({ preventScroll: true });
    if (focusedInput) {
      const input = [...content.querySelectorAll("#operation-form input,#operation-form textarea")].find((element) => element.name === focusedInput.name);
      input == null ? void 0 : input.focus({ preventScroll: true });
      if (input && focusedInput.start !== null) input.setSelectionRange(focusedInput.start, focusedInput.end);
    }
    renderNotices();
  }
  function activity() {
    send("activity");
  }
  function applyAppearance(value) {
    if (value !== "light" && value !== "dark") return;
    const changed = appearance !== value;
    appearance = value;
    if (changed) render();
  }
  function toggleAppearance() {
    if (state.page !== "idle" || state.active) return;
    activity();
    applyAppearance(appearance === "light" ? "dark" : "light");
    send("appearance", { value: appearance });
  }
  function sameLanguage() {
    return state.mode === "translation" && state.language === state.staff_language;
  }
  function translationReadiness() {
    if (state.audio !== "off") return state.audio === "unknown" ? "Microphone release could not be confirmed. Retry after the previous conversation closes." : "Waiting for the previous conversation to release the microphone.";
    if (!state.audioAvailable) return state.audioGate;
    if (sameLanguage()) return "Choose two different languages to start translation.";
    return "Ready. Tap Start translation to turn on the microphone.";
  }
  function page(next) {
    state.page = next;
    render();
  }
  function toggleNight() {
    if (state.page !== "idle" || state.active) return;
    activity();
    state.night = !state.night;
    state.mode = state.night ? "reception" : "translation";
    render();
    send("night", { enabled: state.night });
  }
  function chooseMode(mode) {
    if (state.active || state.audio !== "off") return;
    queuedShortcut = "";
    state.mode = mode;
    state.error = "";
    silence();
    page(mode === "translation" ? "translation_languages" : "languages");
    prepareTranslation();
  }
  function clearGuest() {
    state.utterances.clear();
    captionNodes.clear();
    state.liveText = "";
    state.assistantDraft = "";
    state.localSource = null;
    const transcript = content.querySelector(".conversation");
    if (transcript) transcript.innerHTML = conversation();
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.metrics = "";
    state.notices = [];
    document.getElementById("toast").hidden = true;
    renderNotices();
  }
  function selectLanguage(side, language) {
    state[side] = language;
    selected[side] = true;
    send("languages", { language: state.language, staff_language: state.staff_language });
    prepareTranslation();
  }
  function prepareTranslation() {
    if (state.mode === "translation" && state.audioAvailable && state.page === "translation_languages" && !sameLanguage())
      send("prepare_translation", { language: state.language, staff_language: state.staff_language });
  }
  function start(restart = false) {
    var _a, _b;
    if (state.active || state.phase === "ending" || state.audio !== "off") return;
    if (state.mode === "translation" && !state.audioAvailable) {
      if (native) send("voice_setup");
      else toast(state.audioGate);
      return;
    }
    if (state.mode === "reception" && !state.receptionAvailable) {
      toast(state.receptionGate);
      return;
    }
    if (sameLanguage()) {
      toast("Choose two different languages for translation.");
      return;
    }
    if (!native) {
      state.error = "Voice conversations require the reception tablet. No microphone has been opened.";
      page("error");
      return;
    }
    silence();
    if (!restart) clearGuest();
    else if (state.assistantDraft) {
      state.liveText = streamText();
      state.assistantDraft = "";
    }
    state.pendingPage = "";
    state.micLevel = 0;
    state.sessionId = ((_b = (_a = window.crypto) == null ? void 0 : _a.randomUUID) == null ? void 0 : _b.call(_a)) || "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
      var _a2;
      const value = ((_a2 = window.crypto) == null ? void 0 : _a2.getRandomValues) ? window.crypto.getRandomValues(new Uint8Array(1))[0] & 15 : Math.floor(Math.random() * 16);
      return (character === "x" ? value : value & 3 | 8).toString(16);
    });
    state.generation = null;
    state.closed = false;
    state.terminated = false;
    state.active = true;
    state.phase = "connecting";
    state.error = "";
    page("active");
    send("start", { session_id: state.sessionId, mode: state.mode, language: state.language, staff_language: state.staff_language, greet: greet(state.language) });
  }
  function silence() {
    var _a;
    (_a = window.speechSynthesis) == null ? void 0 : _a.cancel();
    send("silence");
    state.micLevel = 0;
  }
  function chooseShortcut(name) {
    if (!state.active || state.closed || state.mode !== "reception" || state.page !== "active" || ["connecting", "reconnecting", "greeting"].includes(state.phase)) return;
    if (state.demo) {
      addNotice("Hotel requests require a connected reception tablet.", "unavailable");
      return;
    }
    send(name);
  }
  function finishNavigation() {
    if (!state.pendingPage || !state.terminated || state.active || state.audio !== "off") return false;
    const next = state.pendingPage;
    state.pendingPage = "";
    queuedShortcut = "";
    if (next === "idle") clearGuest();
    page(next);
    return true;
  }
  function stop(next = "") {
    if (next) state.pendingPage = next;
    if (!state.active) {
      finishNavigation();
      return;
    }
    if (state.phase === "ending") return;
    silence();
    state.closed = true;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    queuedShortcut = "";
    state.phase = "ending";
    if (state.audio === "recording") state.audio = "stopping";
    page("active");
    send("stop");
  }
  function goBack() {
    if (state.page === "idle") return;
    if (state.active || state.audio !== "off") {
      stop(state.mode === "translation" ? "translation_languages" : "languages");
      return;
    }
    queuedShortcut = "";
    page(state.page === "active" ? state.mode === "translation" ? "translation_languages" : "languages" : "idle");
  }
  let toastTimer;
  function toast(message) {
    const target = document.getElementById("toast");
    target.textContent = message;
    target.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      target.hidden = true;
    }, 5500);
  }
  function perform(name) {
    var _a, _b;
    activity();
    if (name.startsWith("request:")) {
      const request = name.split(":")[1];
      if (state.page !== "idle" || !["checkin", "taxi", "message"].includes(request)) return;
      chooseMode("reception");
      if (state.page === "languages") queuedShortcut = request;
      return;
    }
    if (name.startsWith("pair:")) {
      const [, side, language] = name.split(":");
      if (state.page !== "translation_languages" || state.audio !== "off" || !["language", "staff_language"].includes(side) || !hasOwn(translationLanguages, language)) return;
      selectLanguage(side, language);
      languageSide = side;
      render();
      return;
    }
    if (name.startsWith("language:")) {
      const language = name.split(":")[1];
      if (state.page !== "languages" || !languages[language]) return;
      selectLanguage("language", language);
      start();
      return;
    }
    switch (name) {
      case "appearance":
        toggleAppearance();
        break;
      case "translation":
        chooseMode("translation");
        break;
      case "reception":
        chooseMode("reception");
        break;
      case "assist-go": {
        const language = (_a = document.getElementById("assist-language")) == null ? void 0 : _a.value;
        if (state.page !== "languages" || !languages[language]) return;
        selectLanguage("language", language);
        start();
        break;
      }
      case "start":
        start();
        break;
      case "setup-translation":
        if (native && !state.active && state.audio === "off") send("voice_setup");
        break;
      case "night_setup":
      case "request_location":
        if (native && !state.active && state.audio === "off") send(name);
        break;
      case "mute":
        if (state.active && !state.closed && ["recording", "muted"].includes(state.audio)) {
          activity();
          send("mute", { muted: state.audio !== "muted" });
        }
        break;
      case "restart":
        if (state.terminated && state.audio === "off") start(true);
        break;
      case "stop":
        stop();
        break;
      case "back":
        goBack();
        break;
      case "home":
        if (state.active || state.audio !== "off") stop("idle");
        else {
          clearGuest();
          page("idle");
        }
        break;
      case "retry":
        if (state.mode === "reception") start();
        else page("translation_languages");
        break;
      case "checkin":
      case "operator":
      case "taxi":
      case "question":
        chooseShortcut(name);
        break;
      case "message":
        chooseShortcut("message");
        break;
      case "cancel-operation":
        if (!((_b = state.operation) == null ? void 0 : _b.pending)) {
          state.operation = null;
          page("active");
        }
        break;
      case "edit-draft":
        if (!state.active || state.closed || state.confirmed || !state.operation || !state.draft || state.draft.attempted) return;
        state.draft = null;
        state.operation.pending = false;
        page("operation_form");
        break;
      case "confirm":
      case "retry-draft":
      case "cancel-draft":
        if (!state.active || state.closed || !state.draft || state.confirmed) return;
        if (name === "retry-draft" && !state.draft.attempted) return;
        state.confirmed = true;
        if (name !== "retry-draft") state.draft.approved = name === "confirm";
        state.draft.attempted = true;
        send("confirm", { draft_id: state.draft.draft_id, approved: state.draft.approved });
        render();
        break;
      case "continue":
        state.outcome = null;
        page(state.active && !state.closed ? "active" : "idle");
        break;
    }
  }
  content.addEventListener("click", (event) => {
    const button = event.target.closest("[data-action]");
    if (button && !button.disabled) perform(button.dataset.action);
  });
  content.addEventListener("focusin", (event) => {
    const side = event.target.dataset.languageSide;
    if (!["language", "staff_language"].includes(side)) return;
    languageSide = side;
    content.querySelectorAll(".language-side").forEach((element) => {
      element.dataset.keypad = String(element.querySelector("select").dataset.languageSide === side);
    });
  });
  content.addEventListener("change", (event) => {
    const side = event.target.dataset.languageSide;
    if (side) perform("pair:".concat(side, ":").concat(event.target.value));
  });
  content.addEventListener("input", (event) => {
    const input = event.target;
    if (state.operation && input.closest("#operation-form")) {
      state.operation.values[input.name] = input.value;
      input.setCustomValidity("");
    }
    activity();
  });
  content.addEventListener("submit", (event) => {
    if (event.target.id !== "operation-form") return;
    event.preventDefault();
    if (!state.active || state.closed || !state.operation || state.operation.pending) return;
    for (const input of event.target.querySelectorAll("[required]")) input.setCustomValidity(input.value.trim() ? "" : "Please complete this field.");
    if (!event.target.reportValidity()) return;
    activity();
    const operation = state.operation;
    operation.pending = true;
    send(operation.action, { arguments: { ...operation.values } });
    render();
  });
  document.getElementById("notices").addEventListener("click", (event) => {
    const button = event.target.closest("[data-dismiss]");
    if (!button) return;
    state.notices.splice(Number(button.dataset.dismiss), 1);
    activity();
    renderNotices();
  });
  document.getElementById("night-toggle").addEventListener("click", toggleNight);
  document.getElementById("settings").addEventListener("click", () => send(state.mode === "translation" ? "voice_setup" : "night_setup"));
  document.getElementById("check-updates").addEventListener("click", () => send("check_updates"));
  function hardware(key) {
    activity();
    if (state.page === "translation_languages" && /^[0-9]$/.test(key)) {
      perform("pair:" + languageSide + ":" + Object.keys(translationLanguages)[key === "0" ? 9 : Number(key) - 1]);
      return;
    }
    if (state.page === "languages" && /^[0-9]$/.test(key)) {
      perform("language:" + Object.keys(languages)[key === "0" ? 9 : Number(key) - 1]);
      return;
    }
    if (key === "back" || key === "red" || key === "0") {
      goBack();
      return;
    }
    if (key === "green") {
      if (state.active) stop();
      else if (state.page === "idle") chooseMode(state.night ? "reception" : "translation");
      else if (state.page === "translation_languages") start();
      else if (state.page === "active" && state.terminated) start(true);
      return;
    }
    if (state.page === "idle" && key === "1") toggleAppearance();
    else if (state.page === "idle" && key === "2") chooseMode("reception");
    else if (state.page === "active" && state.mode === "reception" && /^[1-4]$/.test(key)) chooseShortcut(shortcuts[Number(key) - 1]);
  }
  function offerUpdate(version, text, phase, percent) {
    let bar = document.getElementById("update-offer");
    if (!bar) {
      bar = document.createElement("button");
      bar.id = "update-offer";
      bar.type = "button";
      bar.className = "update-offer";
      bar.addEventListener("click", () => {
        if (["checking", "progress", "installing"].includes(bar.dataset.phase)) return;
        const action2 = bar.dataset.phase === "current" ? "check_updates" : "apply_update";
        bar.dataset.phase = action2 === "check_updates" ? "checking" : "installing";
        send(action2);
      });
      document.body.appendChild(bar);
    }
    phase = phase || "ready";
    bar.dataset.phase = phase;
    if (phase === "installed") {
      bar.hidden = true;
      return;
    }
    bar.hidden = false;
    if (["checking", "progress", "installing"].includes(phase)) {
      bar.disabled = true;
      const pct = Number(percent);
      const known = Number.isFinite(pct) && pct >= 0;
      bar.innerHTML = "".concat(esc(text || "Downloading…"), '<span class="track"><span class="fill" style="width:').concat(known ? Math.min(100, pct) : 0, '%"></span></span>');
      return;
    }
    bar.disabled = false;
    if (phase === "current") bar.textContent = text || "Up to date. Tap to check again.";
    else if (phase === "confirm") bar.textContent = text || "Tap to open the installer. The file is already downloaded.";
    else if (phase === "failed") bar.textContent = text || "Update did not finish. Tap to try the installer again.";
    else bar.textContent = text || "Update " + version + " is downloaded. Tap to install.";
  }
  function matchesSession(event) {
    if (!state.sessionId || event.session_id !== state.sessionId || !Number.isInteger(event.generation) || event.generation < 0) return false;
    if (state.generation === null) state.generation = event.generation;
    return event.generation === state.generation;
  }
  function detachGuest(preserveHistory = false) {
    state.closed = true;
    state.terminated = true;
    state.active = false;
    state.phase = preserveHistory ? "stopped" : "";
    state.micLevel = 0;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    queuedShortcut = "";
    if (state.audio !== "off") state.audio = "unknown";
    if (!preserveHistory) {
      clearGuest();
      state.pendingPage = "";
    }
  }
  function receive(event) {
    var _a, _b, _c, _d, _e, _f;
    if (typeof event === "string") {
      try {
        event = JSON.parse(event);
      } catch (e) {
        return;
      }
    }
    if (!event || typeof event !== "object") return;
    const type = event.type;
    if (type === "translation_setup_return") {
      if (!state.active && state.audio === "off") {
        state.mode = "translation";
        page("translation_languages");
      }
      return;
    }
    if (type === "navigation" && event.action === "back") {
      if (state.active && !matchesSession(event)) return;
      goBack();
      return;
    }
    if (type === "appearance") {
      applyAppearance(event.value);
      return;
    }
    if (type === "visibility") {
      if (event.visible === false) {
        detachGuest();
        state.error = "";
        page("idle");
      }
      return;
    }
    if (type === "night_mode") {
      if (typeof event.enabled !== "boolean" || state.night === event.enabled) return;
      state.night = event.enabled;
      if (state.page === "idle") state.mode = state.night ? "reception" : "translation";
      render();
      return;
    }
    if (type === "connection") {
      state.connected = event.connected === true;
      render();
      return;
    }
    if (type === "reception_info") {
      state.hotel = String(event.hotel_name || "");
      state.audioAvailable = typeof event.translation_available === "boolean" ? event.translation_available : event.audio_available === true;
      state.receptionAvailable = typeof event.reception_available === "boolean" ? event.reception_available : true;
      state.openaiConfigured = typeof event.openai_configured === "boolean" ? event.openai_configured : state.audioAvailable;
      if (!state.active && ["off", "unknown"].includes(event.local_audio_state)) state.audio = event.local_audio_state;
      if (typeof event.audio_gate === "string" && event.audio_gate) state.audioGate = event.audio_gate;
      if (typeof event.reception_gate === "string" && event.reception_gate) state.receptionGate = event.reception_gate;
      if (!defaultReceived && !preferencesRestored && !selected.staff_language && hasOwn(languages, event.staff_language)) state.staff_language = event.staff_language;
      defaultReceived = true;
      render();
      return;
    }
    if (type === "language_preferences") {
      for (const side of ["language", "staff_language"]) {
        if (!selected[side] && hasOwn(languages, event[side])) state[side] = event[side];
      }
      preferencesRestored = true;
      render();
      return;
    }
    if (type === "update") {
      offerUpdate(String(event.version || ""));
      return;
    }
    if (type === "update_status") {
      offerUpdate(String(event.version || ""), String(event.text || "Updating…"), String(event.phase || ""), event.percent);
      return;
    }
    if (type === "notice" && !event.session_id) {
      addNotice(event.text, event.status);
      return;
    }
    if (!matchesSession(event)) return;
    if (type === "audio_state") {
      if (!["off", "recording", "stopping", "unknown", "muted"].includes(event.state)) return;
      if (state.closed && !["off", "unknown"].includes(event.state)) return;
      state.audio = event.state;
      if (state.audio !== "recording") state.micLevel = 0;
      if (!finishNavigation()) {
        if (state.page === "active" && !state.terminated) {
          const privacy = document.getElementById("privacy");
          if (privacy) privacy.innerHTML = '<span class="mic-dot'.concat(state.audio === "recording" ? " mic-live" : "", '"></span>').concat(esc(sessionAudioText()));
          syncMic();
          const audioText = content.querySelector(".session-control-copy span");
          if (audioText) audioText.textContent = sessionAudioText();
        } else {
          render();
        }
      }
      return;
    }
    if (state.terminated) return;
    const terminal = type === "reception_event" && ["stopped", "timeout", "error"].includes(event.phase);
    if (state.closed && !terminal) return;
    if (type === "input_activity") {
      activity();
      return;
    }
    if (type === "mic_level" || type === "energy") {
      const level = state.audio === "recording" ? Math.min(1, Math.max(0, Number(event.level) || 0)) : 0;
      state.micLevel = level;
      if (state.audio === "recording" && Array.isArray(event.bands) && event.bands.length >= 3) state.bands = event.bands.slice(0, 3).map((value) => Math.min(1, Math.max(0, Number(value) || 0)));
      else state.bands = [level, level * 0.72, level * 0.4];
      const meter = content.querySelector(".voice-ball");
      meter == null ? void 0 : meter.style.setProperty("--level", String(level));
      (state.bands || []).forEach((band, index) => meter == null ? void 0 : meter.style.setProperty("--b" + index, String(band)));
      return;
    }
    if (type === "notice") {
      addNotice(event.text, event.status);
      if (["error", "failed", "unavailable", "denied", "save_failed"].includes(event.status)) {
        state.confirmed = false;
        if (state.operation) state.operation.pending = false;
        render();
      }
      return;
    }
    if (type === "metrics") {
      state.metrics = String(event.text || "");
      if (state.page === "active") render();
      return;
    }
    if (type === "local_source") {
      if (state.mode === "reception") receiveLocalSource(event);
      return;
    }
    if (type === "translation_text" || type === "assistant_text") {
      if (type === (state.mode === "translation" ? "translation_text" : "assistant_text")) receiveStream(event);
      return;
    }
    if (type === "caption") {
      if (typeof event.utterance_id !== "string" || !event.utterance_id || !["human", "guest", "staff", "assistant"].includes(event.side) || !["original", "text"].includes(event.field) || !["append", "replace"].includes(event.operation) || typeof event.text !== "string" || typeof event.final !== "boolean") return;
      if (state.mode === "translation") return;
      const unlinked = event.association === "unlinked";
      const key = JSON.stringify([state.sessionId, event.utterance_id, unlinked ? event.field : null]);
      let entry = state.utterances.get(key);
      if (!entry) {
        const side = event.side === "assistant" ? "assistant" : unlinked && event.speaker_attribution !== "selected" ? "human" : event.side;
        entry = { id: key, side, association: unlinked ? "unlinked" : "linked", speakerAttribution: side !== "assistant" && event.speaker_attribution === "selected" ? "selected" : "unknown", from: "", to: "", original: "", text: "", final: { original: false, text: false } };
        state.utterances.set(key, entry);
      }
      if (entry.final[event.field] && (!event.final || event.operation === "append")) return;
      entry[event.field] = event.operation === "replace" ? event.text : entry[event.field] + event.text;
      entry.final[event.field] = event.final;
      if (typeof event.from === "string" && event.from) entry.from = event.from;
      if (typeof event.to === "string" && event.to) entry.to = event.to;
      updateCaption(entry);
      if (event.side !== "assistant" && event.text.trim()) activity();
      return;
    }
    if (type === "operation_form") {
      if (![...shortcuts, "message"].includes(event.action) || !Array.isArray(event.fields) || state.draft) return;
      const fields = event.fields.filter((field) => field && typeof field.name === "string" && field.name && typeof field.label === "string");
      if (!fields.length) {
        addNotice("This request did not provide usable fields. Please ask reception for help.", "unavailable");
        return;
      }
      const previous = ((_a = state.operation) == null ? void 0 : _a.action) === event.action ? state.operation : null;
      const allFields = new Map(((previous == null ? void 0 : previous.fields) || []).map((field) => [field.name, field]));
      fields.forEach((field) => allFields.set(field.name, field));
      const emptyValues = /* @__PURE__ */ Object.create(null);
      for (const name of allFields.keys()) emptyValues[name] = "";
      state.operation = { action: event.action, fields: [...allFields.values()], values: { ...emptyValues, ...previous == null ? void 0 : previous.values }, pending: false };
      page("operation_form");
      return;
    }
    if (type === "operation_result") {
      const text = String(event.text || "");
      if (["confirmation_required", "needs_confirmation", "fields_required"].includes(event.status)) return;
      if (["unavailable", "error", "pending"].includes(event.status) && (state.operation || state.draft)) {
        state.confirmed = false;
        if (state.operation) state.operation.pending = false;
        addNotice(text || "No receipt was received. Retry or ask reception to check.", event.status);
        page(state.draft ? "confirming" : "operation_form");
        return;
      }
      if (state.draft && !state.confirmed) {
        addNotice(text, event.status);
        return;
      }
      const receipt = ((_b = event.result) == null ? void 0 : _b.item_id) || ((_c = event.result) == null ? void 0 : _c.request_id) || ((_d = event.result) == null ? void 0 : _d.escalation_id);
      state.outcome = { text: text || "No receipt returned. Ask staff to check.", receipt: typeof receipt === "string" ? "Receipt: ".concat(receipt) : "" };
      state.draft = null;
      state.confirmed = false;
      state.operation = null;
      page("saved");
      return;
    }
    if (type !== "reception_event") return;
    const phase = event.phase;
    if (terminal) {
      const error = phase === "error" ? String(event.text || event.message || "Please ask reception to check the connection and device setup.") : "";
      detachGuest(true);
      state.error = error;
      if (!finishNavigation()) page("active");
      if (error) {
        state.notices.push({ status: "error", text: error });
        renderNotices();
      }
      if (phase === "timeout") toast("Conversation ended after inactivity.");
      return;
    }
    if (phase === "ending") {
      state.closed = true;
      state.micLevel = 0;
      state.draft = null;
      state.operation = null;
      state.phase = "ending";
      if (state.audio === "recording" || state.audio === "muted") state.audio = "stopping";
      page("active");
      return;
    }
    if (phase === "confirming") {
      if (typeof event.draft_id !== "string" || !event.draft_id || ((_e = state.draft) == null ? void 0 : _e.draft_id) === event.draft_id) return;
      state.draft = { draft_id: event.draft_id, guest_name: String(event.guest_name || ""), room_reference: String(event.room_reference || ""), message: String(event.message || "") };
      state.confirmed = false;
      if (state.operation) state.operation.pending = false;
      page("confirming");
      return;
    }
    if (!["connecting", "reconnecting", "ready", "greeting", "listening", "thinking", "speaking"].includes(phase)) return;
    state.phase = phase;
    if (state.mode === "translation" && state.page === "active") {
      syncMic();
      const status = content.querySelector(".translation-status");
      if (status) {
        status.textContent = phaseLabel();
        status.hidden = phase !== "reconnecting";
      }
      return;
    }
    if (state.page === "confirming" && ((_f = state.draft) == null ? void 0 : _f.draft_id.startsWith("voice:")) && state.confirmed && phase === "listening") {
      state.draft = null;
      state.confirmed = false;
      state.page = "active";
    }
    if (["confirming", "saved", "operation_form"].includes(state.page)) return;
    page("active");
    if (["ready", "listening"].includes(phase) && queuedShortcut) {
      const request = queuedShortcut;
      queuedShortcut = "";
      if (request === "message") perform("message");
      else chooseShortcut(request);
    }
  }
  window.manzania = Object.freeze({ receive });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      hardware("back");
      return;
    }
    if (event.target.matches("input,textarea")) return;
    if (/^[0-9]$/.test(event.key)) {
      event.preventDefault();
      hardware(event.key);
    }
  });
  document.addEventListener("pointerdown", activity, { passive: true });
  document.addEventListener("wheel", activity, { passive: true });
  function hideGuest() {
    silence();
    if (state.active) send("stop");
    detachGuest();
    state.error = "";
    page("idle");
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) hideGuest();
  });
  window.addEventListener("pagehide", hideGuest);
  function updateClock() {
    const now = /* @__PURE__ */ new Date();
    document.getElementById("date").textContent = new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short" }).format(now);
    document.getElementById("time").textContent = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" }).format(now);
  }
  updateClock();
  setInterval(updateClock, 6e4);
  render();
  send("info");
})();
