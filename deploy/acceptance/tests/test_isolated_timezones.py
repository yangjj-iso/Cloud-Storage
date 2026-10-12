"""Guard the disposable topology clock contract; real plan expiry is tested in CI."""
from pathlib import Path
import unittest
from urllib.parse import parse_qs


class IsolatedTimezoneTests(unittest.TestCase):
    def test_jvm_jdbc_mysql_and_sessions_use_utc_without_disabling_conversion(self):
        text = (Path(__file__).resolve().parents[1] / 'compose.yml').read_text()
        app_env = text.split('x-app-env: &app-env\n', 1)[1].split('\nservices:', 1)[0]
        self.assertIn('  TZ: UTC\n', app_env)
        self.assertIn('  JAVA_TOOL_OPTIONS: -Duser.timezone=UTC\n', app_env)
        url = next(line.split(': ', 1)[1] for line in app_env.splitlines()
                   if line.strip().startswith('SPRING_DATASOURCE_URL:'))
        query = parse_qs(url.split('?', 1)[1])
        self.assertEqual(query['connectionTimeZone'], ['UTC'])
        self.assertEqual(query['forceConnectionTimeZoneToSession'], ['true'])
        self.assertNotIn('serverTimezone', query)
        self.assertNotEqual(query.get('preserveInstants'), ['false'])
        self.assertEqual(query['sslMode'], ['REQUIRED'])
        mysql = text.split('\n  mysql:\n', 1)[1].split('\n  redis:', 1)[0]
        self.assertIn("'--default-time-zone=+00:00'", mysql)
        for service, following in [('api', 'worker'), ('worker', 'acceptance')]:
            block = text.split('\n  ' + service + ':\n', 1)[1].split('\n  ' + following + ':', 1)[0]
            self.assertIn('<<: *app-env', block)


if __name__ == '__main__':
    unittest.main()
