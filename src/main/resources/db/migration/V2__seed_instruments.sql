INSERT INTO asset (code, name) VALUES
    ('BTC', '비트코인'),
    ('ETH', '이더리움');

INSERT INTO market (asset_id, exchange, symbol, quote_currency, role) VALUES
    ((SELECT id FROM asset WHERE code = 'BTC'), 'UPBIT',   'KRW-BTC', 'KRW',  'EXECUTION'),
    ((SELECT id FROM asset WHERE code = 'ETH'), 'UPBIT',   'KRW-ETH', 'KRW',  'EXECUTION'),
    ((SELECT id FROM asset WHERE code = 'BTC'), 'BINANCE', 'BTCUSDT', 'USDT', 'REFERENCE'),
    ((SELECT id FROM asset WHERE code = 'ETH'), 'BINANCE', 'ETHUSDT', 'USDT', 'REFERENCE');
