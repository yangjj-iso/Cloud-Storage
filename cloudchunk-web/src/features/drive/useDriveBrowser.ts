import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import type { UserFileItem } from '../../types';

export function useDriveBrowser() {
  const [path, setPath] = useState([{ id: 0, name: '我的云盘' }]);
  const [files, setFiles] = useState<UserFileItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const sequence = useRef(0);
  const parentId = path[path.length - 1].id;
  const refresh = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    const request = ++sequence.current;
    setFiles([]);
    setLoading(true);
    setError('');
    api
      .driveList(parentId)
      .then((result) => {
        if (sequence.current === request) setFiles(result);
      })
      .catch((reason) => {
        if (sequence.current === request) setError(reason instanceof Error ? reason.message : '加载失败');
      })
      .finally(() => {
        if (sequence.current === request) setLoading(false);
      });
    return () => {
      sequence.current++;
    };
  }, [parentId, revision]);
  return {
    path,
    files,
    loading,
    error,
    parentId,
    refresh,
    open: (folder: UserFileItem) => setPath((value) => [...value, { id: folder.id, name: folder.fileName }]),
    backTo: (index: number) => setPath((value) => value.slice(0, index + 1)),
  };
}
