"""无网络的拓扑契约回归；真实Broker与Prometheus由messaging-recovery.py覆盖。"""
import copy
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('rabbit_exporter', ROOT / 'scripts/Rabbit-Exporter.py')
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)

class TopologyContractTest(unittest.TestCase):
    def setUp(self): self.actual = copy.deepcopy(exporter.EXPECTED)
    def test_expected_topology_is_complete(self):
        self.assertEqual(8, len(self.actual['queues']))
        self.assertEqual(14, len(self.actual['bindings']))
        self.assertTrue(all(exporter.topology_checks(self.actual).values()))
    def test_one_missing_fanout_binding_is_not_hidden_by_other_target(self):
        self.actual['bindings'] = [row for row in self.actual['bindings'] if not (row['destination']=='q.aftersale.refund' and row['routing_key']=='refund.succeeded.v1')]
        checks = exporter.topology_checks(self.actual)
        self.assertEqual(0, checks['binding', 'tradeflow.events>q.aftersale.refund:refund.succeeded.v1'])
        self.assertEqual(1, checks['binding', 'tradeflow.events>q.order.refund:refund.succeeded.v1'])
        self.assertEqual(0, checks['binding-set', 'tradeflow.events'])
    def test_non_durable_exchange_fails(self):
        self.actual['exchanges'][0]['durable'] = False
        self.assertEqual(0, exporter.topology_checks(self.actual)['exchange', 'tradeflow.events'])
    def test_extra_queue_expiration_fails(self):
        self.actual['queues'][0]['arguments']['x-message-ttl'] = 1
        self.assertEqual(0, exporter.topology_checks(self.actual)['queue', 'q.order.payment'])
    def test_effective_policy_fails(self):
        self.actual['queues'][0]['effective_policy_definition'] = {'max-length': 1}
        self.assertEqual(0, exporter.topology_checks(self.actual)['queue', 'q.order.payment'])
    def test_missing_queue_is_known_fault(self):
        self.actual['queues'].pop(0)
        self.assertEqual(0, exporter.topology_checks(self.actual)['queue', 'q.order.payment'])
    def test_unexpected_binding_has_bounded_fixed_label(self):
        row = dict(self.actual['bindings'][0]); row['destination'] = 'untrusted-dynamic-name'
        self.actual['bindings'].append(row)
        checks = exporter.topology_checks(self.actual)
        self.assertEqual(0, checks['binding-set', 'tradeflow.events'])
        self.assertFalse(any('untrusted' in resource for _, resource in checks))
    def test_failed_sampling_is_nan_and_unhealthy(self):
        rendered = exporter.render({'values':{}, 'topology':{key:'NaN' for key in exporter.topology_checks(self.actual)}, 'success':0, 'last':123}).decode()
        self.assertIn('tradeflow_rabbitmq_topology_valid NaN', rendered)
        self.assertIn('tradeflow_rabbitmq_collection_success 0', rendered)
        self.assertIn('tradeflow_rabbitmq_last_success_unixtime_seconds 123', rendered)

if __name__ == '__main__': unittest.main()
