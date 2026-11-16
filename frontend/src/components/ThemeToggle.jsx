import { SunIcon, MoonIcon } from '../icons.jsx';

export default function ThemeToggle({ theme, onToggle }) {
  const dark = theme === 'dark';
  return (
    <button
      type="button"
      className={`theme-toggle${dark ? ' is-dark' : ''}`}
      onClick={onToggle}
      role="switch"
      aria-checked={dark}
      aria-label={dark ? 'Switch to day mode' : 'Switch to night mode'}
      title={dark ? 'Day mode' : 'Night mode'}
    >
      <span className="tt-track">
        <span className="tt-icon tt-sun">
          <SunIcon size={14} />
        </span>
        <span className="tt-icon tt-moon">
          <MoonIcon size={14} />
        </span>
        <span className="tt-thumb">{dark ? <MoonIcon size={14} /> : <SunIcon size={14} />}</span>
      </span>
      <span className="tt-label">{dark ? 'Night' : 'Day'}</span>
    </button>
  );
}
