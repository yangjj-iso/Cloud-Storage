"""Synthetic runner contracts; these do not claim actual provider execution."""
import unittest
from test_staging_journey import m, staging


class ModelJourneyTests(unittest.TestCase):
    def test_model_contract_uses_two_calls_and_separate_review_execute_undo(self):
        journey, state = self.model_contract()
        journey.model({'userFileId': '7', 'generation': 1, 'chunkIndex': 0}, '7', '8')
        self.assertEqual(state['provider'], ['/knowledge/assistant/answer', '/knowledge/assistant/move/suggest'])
        self.assertEqual(state['actions'], ['approve', 'execute', 'undo'])
        self.assertEqual(state['parent'], '0')
        self.assertEqual(journey.report['model'], 'passed')
        self.assertEqual(journey.report['modelAttemptedCalls'], 2)
        self.assertEqual([s['name'] for s in journey.report['stages']], list(m.MODEL_STAGES))
        self.assertTrue(all(s['passed'] for s in journey.report['stages']))

    def test_model_contract_rejects_bad_citation_without_second_provider_call(self):
        journey, state = self.model_contract(bad_citation=True)
        with self.assertRaisesRegex(m.AcceptanceError, 'model-citation-failed'):
            journey.model({'userFileId': '7', 'generation': 1, 'chunkIndex': 0}, '7', '8')
        self.assertEqual(len(state['provider']), 1)
        self.assertEqual(state['actions'], [])
        self.assertNotEqual(journey.report['model'], 'passed')

    def test_model_contract_never_retries_provider_failure(self):
        journey, state = self.model_contract(provider_failure=True)
        with self.assertRaisesRegex(m.AcceptanceError, 'transport-error-no-write-retry'):
            journey.model({'userFileId': '7', 'generation': 1, 'chunkIndex': 0}, '7', '8')
        self.assertEqual(len(state['provider']), 1)
        self.assertEqual(state['actions'], [])
        self.assertNotEqual(journey.report['model'], 'passed')

    def test_model_contract_rejects_unexpected_call_count_or_utc_rollover(self):
        for used, day in [(1, '2026-01-01'), (3, '2026-01-01'), (2, '2026-01-02')]:
            journey, state = self.model_contract(final_used=used, final_day=day)
            with self.assertRaisesRegex(m.AcceptanceError, 'model-call-count-mismatch'):
                journey.model({'userFileId': '7', 'generation': 1, 'chunkIndex': 0}, '7', '8')
            self.assertEqual(state['parent'], '0')
            self.assertNotEqual(journey.report['model'], 'passed')

    def test_model_contract_rejects_approval_side_effect_before_execute(self):
        journey, state = self.model_contract(approval_moves=True)
        with self.assertRaisesRegex(m.AcceptanceError, 'model-approval-moved-file'):
            journey.model({'userFileId': '7', 'generation': 1, 'chunkIndex': 0}, '7', '8')
        self.assertEqual(state['actions'], ['approve'])
        self.assertNotEqual(journey.report['model'], 'passed')

    def model_contract(self, *, bad_citation=False, provider_failure=False,
                       final_used=2, final_day='2026-01-01', approval_moves=False):
        # Synthetic HTTP response contracts, not a live provider claim.
        config = m.Config.from_env({**staging(), 'M47_MODEL': 'true',
                                   'M47_MODEL_CONSENT': 'ALLOW_TWO_SYNTHETIC_MODEL_CALLS'})
        journey = m.Journey(config, {'stages': [], 'model': 'required'})
        state = {'provider': [], 'actions': [], 'parent': '0', 'usage': 0}
        def call(method, path, body=None, **kwargs):
            if path.endswith('/usage'):
                state['usage'] += 1
                return {'dayUtc': '2026-01-01' if state['usage'] == 1 else final_day,
                        'chat': {'remaining': 2, 'used': 0 if state['usage'] == 1 else final_used}}
            if path.endswith('/capabilities'):
                return {'enabled': True}
            if path.endswith('/answer') or path.endswith('/move/suggest'):
                self.assertEqual(method, 'POST')
                self.assertIs(body['consent'], True)
                state['provider'].append(path)
                if provider_failure:
                    raise m.AcceptanceError('transport-error-no-write-retry')
                if path.endswith('/answer'):
                    self.assertEqual(body['sources'], [{'userFileId': '7', 'generation': 1, 'chunkIndex': 0}])
                    return {'mode': 'MODEL_WITH_CITATIONS', 'answer': 'Archive [1].',
                            'citations': [{'evidence': {'userFileId': '9' if bad_citation else '7'}}]}
                self.assertEqual(body['candidateFolderIds'], [8])
                return {'id': 'proposal', 'userFileId': '7', 'destinationId': 8, 'status': 'PENDING'}
            if path == '/knowledge/assistant/workflows':
                self.assertEqual(body, {'moveProposalIds': ['proposal'], 'confirmed': True})
                return {'summary': {'id': 'workflow'}}
            for action in ('approve', 'execute', 'undo'):
                if path == '/knowledge/assistant/workflows/workflow/steps/1/' + action:
                    self.assertEqual(method, 'POST')
                    self.assertEqual(body, {'confirmed': True})
                    state['actions'].append(action)
                    if action == 'execute' or (action == 'approve' and approval_moves):
                        state['parent'] = '8'
                    elif action == 'undo':
                        state['parent'] = '0'
                    return {}
            self.fail('Unexpected model contract request: ' + method + ' ' + path)
        journey.call = call
        journey.contains = lambda parent, uid: uid == '7' and parent == state['parent']
        return journey, state


if __name__ == '__main__':
    unittest.main()
