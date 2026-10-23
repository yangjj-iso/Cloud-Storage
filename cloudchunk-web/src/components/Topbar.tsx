import { useEffect, useRef, useState } from 'react';
import { useAppStore } from '../store';
import { navigation, viewLabel } from '../lib/navigation';
import { MaterialIcon } from './ui/MaterialIcon';
import { AccountSettingsDialog } from './AccountSettingsDialog';
import { roleLabel } from '../lib/locale';
import { Brand } from './ui/Brand';

export function Topbar({ onSearch }: { onSearch: () => void }) {
  const view = useAppStore((s) => s.view);
  const setView = useAppStore((s) => s.setView);
  const user = useAppStore((s) => s.authUser);
  const logout = useAppStore((s) => s.logout);
  const [menuOpen, setMenuOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const outside = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setMenuOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false);
        setMobileOpen(false);
      }
    };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, []);
  useEffect(() => {
    setMobileOpen(false);
  }, [view]);
  const name = user?.nickname || user?.username || '用户';
  return (
    <>
      <header className="app-topbar">
        <div className="topbar-location">
          <span className="desktop-location">
            工作空间<MaterialIcon>chevron_right</MaterialIcon>
            <strong>{viewLabel(view)}</strong>
          </span>
          <span className="mobile-brand">
            <Brand compact />
          </span>
        </div>
        <div className="topbar-actions">
          <button className="global-search" type="button" aria-label="搜索知识资料" onClick={onSearch}>
            <MaterialIcon>search</MaterialIcon>
            <span>搜索知识资料</span>
            <kbd>⌘ K</kbd>
          </button>
          <button
            type="button"
            className="stitch-icon-button"
            aria-label="账户设置"
            onClick={() => setSettingsOpen(true)}
          >
            <MaterialIcon>settings</MaterialIcon>
          </button>
          <div className="account-area" ref={menuRef}>
            <button
              type="button"
              className="account-trigger"
              aria-label="账户菜单"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((v) => !v)}
            >
              <span className="avatar">{name.slice(0, 1).toUpperCase()}</span>
              <span className="account-name">{name}</span>
              <MaterialIcon>expand_more</MaterialIcon>
            </button>
            {menuOpen && (
              <div className="account-menu">
                <strong>{name}</strong>
                <small>{user?.email || roleLabel(user?.role)}</small>
                <button
                  type="button"
                  onClick={() => {
                    setMenuOpen(false);
                    setSettingsOpen(true);
                  }}
                >
                  <MaterialIcon>settings</MaterialIcon>账户设置
                </button>
                <button type="button" onClick={() => void logout()}>
                  <MaterialIcon>logout</MaterialIcon>退出登录
                </button>
              </div>
            )}
          </div>
          <button
            type="button"
            className="stitch-icon-button mobile-menu-trigger"
            aria-label="打开导航"
            aria-expanded={mobileOpen}
            onClick={() => setMobileOpen((v) => !v)}
          >
            <MaterialIcon>{mobileOpen ? 'close' : 'menu'}</MaterialIcon>
          </button>
        </div>
      </header>
      {mobileOpen && (
        <nav className="mobile-navigation" aria-label="移动端页面导航">
          {navigation.map((item) => (
            <button
              key={item.id}
              type="button"
              aria-current={view === item.id ? 'page' : undefined}
              onClick={() => {
                setView(item.id);
                setMobileOpen(false);
              }}
            >
              <MaterialIcon>{item.icon}</MaterialIcon>
              {item.label}
            </button>
          ))}
          {user?.role === 'admin' && (
            <button
              type="button"
              onClick={() => {
                setView('admin');
                setMobileOpen(false);
              }}
            >
              <MaterialIcon>admin_panel_settings</MaterialIcon>管理后台
            </button>
          )}
        </nav>
      )}
      {settingsOpen && <AccountSettingsDialog onClose={() => setSettingsOpen(false)} />}
    </>
  );
}
