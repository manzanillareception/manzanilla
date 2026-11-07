"""The provider-neutral language evidence module loads before the reception UI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def test_language_evidence_module_is_loaded_before_reception():
    source = (ROOT / 'android/hotel/src/main/assets/reception/index.html').read_text()
    assert source.index('src="language-turns.js"') < source.index('src="reception.js"')
