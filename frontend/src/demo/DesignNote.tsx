import { useState, type ReactNode } from 'react';
import { DEMO } from './flag';

// 시연용 "설계 포인트" 표시. 작은 칩으로 두고 눌렀을 때만 설명을 펼친다 (화면을 가리지 않게)
export function DesignNote({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  if (!DEMO) return null;
  return (
    <div className="design-note">
      <button type="button" className="design-chip" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className="design-chip-mark">설계</span>
        {title}
        <span className="design-chip-arrow">{open ? '−' : '+'}</span>
      </button>
      {open && <div className="design-body">{children}</div>}
    </div>
  );
}
