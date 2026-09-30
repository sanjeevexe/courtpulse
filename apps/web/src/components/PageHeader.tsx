import type { ReactNode } from 'react';

/** The one page-title pattern: eyebrow, title, optional lede, and actions aligned to the title. */
export function PageHeader({ eyebrow, title, lede, actions, titleId }: {
  eyebrow?: string;
  title: string;
  lede?: ReactNode;
  actions?: ReactNode;
  titleId?: string;
}) {
  return (
    <header className="page-header">
      <div className="page-header__text">
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1 id={titleId}>{title}</h1>
        {lede ? <p className="page-header__lede">{lede}</p> : null}
      </div>
      {actions ? <div className="page-header__actions">{actions}</div> : null}
    </header>
  );
}
