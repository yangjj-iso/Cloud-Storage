import { Component, type ReactNode } from 'react';
import { AlertCircle } from 'lucide-react';

export class PageBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    if (this.state.failed)
      return (
        <div className="page-fallback" role="alert">
          <AlertCircle size={32} />
          <h2>页面暂时无法打开</h2>
          <button className="forge-button primary" onClick={() => window.location.reload()}>
            重新加载
          </button>
        </div>
      );
    return this.props.children;
  }
}
