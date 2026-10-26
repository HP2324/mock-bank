import { useParams } from 'react-router-dom';
import { api } from '../api';
import { MemberDetail } from '../components/MemberDetail';
import { Banner, bannerTypeFor, Panel, usePageTitle } from '../components/ui';
import { useApi } from '../useApi';

export function MemberDetailPage() {
  const { memberId } = useParams();
  usePageTitle(`Member ${memberId}`);
  const member = useApi(() => api.getMember(memberId), [memberId]);

  if (member.status === 'ready') {
    return <MemberDetail member={member.data} />;
  }
  return (
    <Panel title="Member detail">
      {member.status === 'loading' && <p className="muted">Loading member…</p>}
      {member.status === 'error' && <Banner type={bannerTypeFor(member.error)}>{member.error.message}</Banner>}
    </Panel>
  );
}
