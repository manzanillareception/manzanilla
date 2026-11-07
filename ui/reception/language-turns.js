/* Language evidence owns placement. Text, pauses and output order never choose a side. */
(function (root) {
  'use strict';
  const normalize = value => typeof value === 'string' ? value.trim().toLowerCase().replace(/_/g, '-').split('-')[0] : '';
  const sideFor = (language, guest, staff) => {
    const code = normalize(language);
    if (!code || normalize(guest) === normalize(staff)) return null;
    if (code === normalize(staff)) return 'staff';
    if (code === normalize(guest)) return 'guest';
    return null;
  };
  class LanguageTurns {
    constructor() { this.turns = []; this.byId = new Map(); }
    clear() { this.turns.length = 0; this.byId.clear(); }
    key(event, kind, id = event.utterance_id) { return JSON.stringify([event.session_id || '', event.generation ?? null, kind, id]); }
    add(id, kind, scope) {
      const turn = { id, kind, scope, side: null, sourceLanguage: '', original: '', text: '', final: { original: false, text: false }, reported: false, node: null };
      this.byId.set(id, turn);
      this.turns.push(turn);
      return turn;
    }
    resolve(guest, staff, changed) {
      for (const turn of this.turns) {
        if (turn.kind !== 'human') continue;
        const side = sideFor(turn.sourceLanguage, guest, staff);
        if (turn.side !== side) { turn.side = side; changed.add(turn); }
      }
      return [...changed];
    }
    accept(event, guest, staff) {
      if (!event || typeof event.utterance_id !== 'string' || !event.utterance_id) return [];
      const changed = new Set();
      const scope = JSON.stringify([event.session_id || '', event.generation ?? null]);
      if (event.type === 'interpretation') {
        if (typeof event.original !== 'string' || !event.original.trim() || typeof event.text !== 'string' ||
            typeof event.source_language !== 'string' || event.language_attribution !== 'model') return [];
        const language = normalize(event.source_language);
        if (language !== 'und' && !sideFor(language, guest, staff)) return [];
        if ((language === 'und') !== !event.text.trim()) return [];
        const id = this.key(event, 'human');
        const existing = this.byId.get(id);
        if (existing?.reported) return []; // A tool call is one immutable utterance, including repeats.
        const turn = existing || this.add(id, 'human', scope);
        Object.assign(turn, { original: event.original, text: language === 'und' ? '' : event.text, sourceLanguage: language, reported: true, final: { original: true, text: true } });
        changed.add(turn);
      } else if (event.type === 'caption') {
        if (!['human', 'guest', 'staff', 'assistant'].includes(event.side) || !['original', 'text'].includes(event.field) ||
            !['append', 'replace'].includes(event.operation) || typeof event.text !== 'string' || typeof event.final !== 'boolean') return [];
        const assistant = event.side === 'assistant';
        if (event.field !== (assistant ? 'text' : 'original')) return [];
        // An output can join a source only through an explicit reference. Provider segment IDs
        // and the latest bubble are not translation links. Unlinked output stays neutral.
        const parent = assistant && typeof event.source_utterance_id === 'string' ? this.byId.get(this.key(event, 'human', event.source_utterance_id)) : null;
        const id = this.key(event, assistant ? 'assistant' : 'human');
        const turn = parent || this.byId.get(id) || this.add(id, assistant ? 'assistant' : 'human', scope);
        if (turn.reported) return [];
        if (!assistant && event.language_attribution === 'model' && sideFor(event.source_language, guest, staff)) {
          turn.sourceLanguage = normalize(event.source_language);
          changed.add(turn); // Language metadata may arrive after text finality or output.
        }
        if (!(turn.final[event.field] && (!event.final || event.operation === 'append'))) {
          turn[event.field] = event.operation === 'replace' ? event.text : turn[event.field] + event.text;
          turn.final[event.field] ||= event.final;
          changed.add(turn);
        }
      } else return [];
      return this.resolve(guest, staff, changed);
    }
  }
  if (typeof module === 'object' && module.exports) module.exports = { LanguageTurns, normalize, sideFor };
  else root.ManzanillaLanguageTurns = LanguageTurns;
})(typeof window === 'object' ? window : globalThis);
