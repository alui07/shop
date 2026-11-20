-- Demo data for local development and e2e; applied only with the `seed` profile.

INSERT INTO category (slug, name) VALUES
    ('kitchen', 'Kitchen'),
    ('garden', 'Garden'),
    ('home-office', 'Home Office'),
    ('outdoor', 'Outdoor'),
    ('lighting', 'Lighting'),
    ('bath', 'Bath')
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name;

WITH seed (slug, name, description, price_minor, category_slug, on_hand) AS (VALUES
    ('oak-cutting-board', 'Oak Cutting Board', 'Solid oak chopping board with a juice groove, oiled and ready for daily use.', 3490, 'kitchen', 10),
    ('ceramic-mixing-bowl', 'Ceramic Mixing Bowl', 'Glazed stoneware bowl, large enough for dough and dishwasher safe.', 2490, 'kitchen', 17),
    ('cast-iron-skillet', 'Cast Iron Skillet', 'Pre-seasoned 26 cm skillet that moves from the stove to the oven and the table.', 4900, 'kitchen', 24),
    ('copper-kettle', 'Copper Kettle', 'Stovetop kettle with a stay-cool handle and a whistling spout.', 5900, 'kitchen', 31),
    ('chef-knife', 'Chef Knife', 'Forged steel knife, 20 cm blade, balanced for slicing vegetables and herbs.', 7900, 'kitchen', 38),
    ('linen-apron', 'Linen Apron', 'Washed linen apron with an adjustable neck strap and a deep front pocket.', 2490, 'kitchen', 45),
    ('glass-storage-jars', 'Glass Storage Jars', 'Set of four airtight jars for flour, rice, pasta and spices.', 3290, 'kitchen', 11),
    ('wooden-spoon-set', 'Wooden Spoon Set', 'Five beech spoons and spatulas that are gentle on non-stick pans.', 1890, 'kitchen', 18),
    ('espresso-maker', 'Espresso Maker', 'Stovetop espresso maker brewing three cups of rich coffee.', 3490, 'kitchen', 25),
    ('herb-scissors', 'Herb Scissors', 'Five-blade scissors that chop fresh herbs in seconds.', 1290, 'kitchen', 32),
    ('terracotta-planter', 'Terracotta Planter', 'Frost-proof terracotta pot with a drainage hole for herbs and flowers.', 1990, 'garden', 39),
    ('watering-can', 'Watering Can', 'Galvanised steel can with a long spout and a removable rose.', 2990, 'garden', 46),
    ('pruning-shears', 'Pruning Shears', 'Bypass shears with hardened steel blades for clean cuts on branches.', 2490, 'garden', 12),
    ('garden-gloves', 'Garden Gloves', 'Breathable leather gloves that protect against thorns and splinters.', 1590, 'garden', 19),
    ('raised-bed-kit', 'Raised Bed Kit', 'Cedar raised bed, 120 by 60 cm, assembled without tools.', 8900, 'garden', 26),
    ('bird-feeder', 'Bird Feeder', 'Hanging feeder in oak and steel that welcomes finches and tits.', 2290, 'garden', 33),
    ('hand-trowel', 'Hand Trowel', 'Stainless steel trowel with an ash handle for planting and weeding.', 1490, 'garden', 40),
    ('seed-starter-tray', 'Seed Starter Tray', 'Twelve-cell tray with a clear lid for sowing seedlings indoors.', 1290, 'garden', 47),
    ('garden-kneeler', 'Garden Kneeler', 'Foam kneeler that folds into a bench for tending the flower beds.', 3990, 'garden', 13),
    ('compost-bin', 'Compost Bin', 'Sixty litre bin with a lockable lid to turn kitchen scraps into soil.', 4990, 'garden', 20),
    ('oak-desk', 'Oak Desk', 'Solid oak desk, 140 cm wide, with a cable tray and a soft matte finish.', 32900, 'home-office', 27),
    ('ergonomic-chair', 'Ergonomic Chair', 'Mesh back chair with lumbar support and adjustable arms for long days.', 24900, 'home-office', 34),
    ('desk-lamp', 'Desk Lamp', 'Dimmable LED lamp with an adjustable arm and a warm reading mode.', 4900, 'home-office', 41),
    ('monitor-stand', 'Monitor Stand', 'Bamboo riser that lifts the screen to eye level and hides a keyboard.', 3990, 'home-office', 48),
    ('notebook-set', 'Notebook Set', 'Three dotted notebooks with stitched binding and thick paper.', 1890, 'home-office', 14),
    ('felt-desk-mat', 'Felt Desk Mat', 'Wool felt mat that protects the desk and keeps the mouse steady.', 2990, 'home-office', 21),
    ('cable-organiser', 'Cable Organiser', 'Silicone clips that keep charging cables tidy on the desk.', 990, 'home-office', 28),
    ('pen-holder', 'Pen Holder', 'Turned oak holder for pens, pencils and a pair of scissors.', 1890, 'home-office', 35),
    ('wall-planner', 'Wall Planner', 'Annual planner printed on recycled paper with space for notes.', 1490, 'home-office', 42),
    ('laptop-sleeve', 'Laptop Sleeve', 'Padded felt sleeve with a zip pocket for a charger and notebook.', 3490, 'home-office', 49),
    ('camping-tent', 'Camping Tent', 'Two person tent with a waterproof flysheet that packs down small.', 18900, 'outdoor', 15),
    ('sleeping-bag', 'Sleeping Bag', 'Three season sleeping bag with a warm hood and a compression sack.', 12900, 'outdoor', 22),
    ('trail-backpack', 'Trail Backpack', 'Thirty five litre pack with a rain cover and a ventilated back.', 9900, 'outdoor', 29),
    ('insulated-bottle', 'Insulated Bottle', 'Steel bottle that keeps water cold for a day and tea hot for hours.', 2490, 'outdoor', 36),
    ('camp-stove', 'Camp Stove', 'Compact gas stove that boils a litre of water in three minutes.', 4490, 'outdoor', 43),
    ('folding-chair', 'Folding Chair', 'Lightweight chair with an aluminium frame for camp and beach.', 3490, 'outdoor', 50),
    ('headtorch', 'Headtorch', 'Rechargeable headlamp with a red night mode and a tilting beam.', 2990, 'outdoor', 16),
    ('picnic-blanket', 'Picnic Blanket', 'Water resistant blanket with a woollen top and a padded bag.', 3490, 'outdoor', 23),
    ('trekking-poles', 'Trekking Poles', 'Pair of carbon poles with cork grips and quick locks.', 8900, 'outdoor', 30),
    ('dry-bag', 'Dry Bag', 'Roll-top bag that keeps clothes and a camera dry in a canoe.', 1990, 'outdoor', 37),
    ('brass-floor-lamp', 'Brass Floor Lamp', 'Tall floor lamp with a linen shade and a warm brass base.', 15900, 'lighting', 44),
    ('paper-pendant', 'Paper Pendant', 'Round paper shade that spreads a soft glow across the dining table.', 4490, 'lighting', 10),
    ('glass-table-lamp', 'Glass Table Lamp', 'Hand blown glass lamp with a dimmer on the cable.', 6900, 'lighting', 17),
    ('string-lights', 'String Lights', 'Ten metres of warm white lights for the garden or a bedroom wall.', 2490, 'lighting', 24),
    ('oak-wall-sconce', 'Oak Wall Sconce', 'Wall light with an oak backplate and a switch on the arm.', 5900, 'lighting', 31),
    ('led-bulb-pack', 'LED Bulb Pack', 'Pack of four dimmable bulbs with a warm white tone.', 1490, 'lighting', 38),
    ('candle-lantern', 'Candle Lantern', 'Steel lantern with a glass door for candles on the patio.', 2990, 'lighting', 45),
    ('night-light', 'Night Light', 'Plug-in light that turns on at dusk and gives a gentle glow.', 1290, 'lighting', 11),
    ('track-spotlight', 'Track Spotlight', 'Three head spotlight that swivels to light a wall or a shelf.', 7900, 'lighting', 18),
    ('solar-path-lights', 'Solar Path Lights', 'Set of six stakes that charge by day and light the path at night.', 3990, 'lighting', 25),
    ('cotton-bath-towel', 'Cotton Bath Towel', 'Heavy cotton towel, soft after the first wash and quick to dry.', 2490, 'bath', 32),
    ('linen-shower-curtain', 'Linen Shower Curtain', 'Water repellent linen curtain with reinforced eyelets.', 3490, 'bath', 39),
    ('bamboo-bath-mat', 'Bamboo Bath Mat', 'Slatted mat that dries fast and does not slip on tiles.', 2990, 'bath', 46),
    ('soap-dish', 'Soap Dish', 'Ceramic dish with a drainage ridge so soap stays firm.', 1290, 'bath', 12),
    ('olive-soap-bar', 'Olive Soap Bar', 'Cold process soap with olive oil and a touch of lavender.', 690, 'bath', 19),
    ('wooden-bath-caddy', 'Wooden Bath Caddy', 'Oak caddy that spans the tub and holds a book and a candle.', 5900, 'bath', 26),
    ('hand-towel-set', 'Hand Towel Set', 'Set of three cotton hand towels in sand, grey and white.', 2490, 'bath', 33),
    ('bristle-brush', 'Bristle Brush', 'Beech brush with soft bristles for dry brushing before a shower.', 1490, 'bath', 40),
    ('glass-toothbrush-cup', 'Glass Toothbrush Cup', 'Heavy glass cup that sits steady on the basin.', 990, 'bath', 47),
    ('laundry-hamper', 'Laundry Hamper', 'Woven hamper with a cotton liner and a lid that closes quietly.', 4490, 'bath', 13)
),
products AS (
    INSERT INTO product (slug, name, description, price_minor, currency, category_id, active)
    SELECT s.slug, s.name, s.description, s.price_minor, 'EUR', c.id, true
    FROM seed s
    JOIN category c ON c.slug = s.category_slug
    ON CONFLICT (slug) DO UPDATE SET
        name = EXCLUDED.name,
        description = EXCLUDED.description,
        price_minor = EXCLUDED.price_minor,
        currency = EXCLUDED.currency,
        category_id = EXCLUDED.category_id,
        active = true,
        version = product.version + 1
    WHERE (product.name, product.description, product.price_minor, product.currency, product.category_id, product.active)
        IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.description, EXCLUDED.price_minor, EXCLUDED.currency, EXCLUDED.category_id, EXCLUDED.active)
    RETURNING id, slug
),
-- Rows the upsert left alone are not returned, and a statement cannot see its own inserts, so both sources are needed.
seeded_products AS (
    SELECT id, slug FROM products
    UNION
    SELECT p.id, p.slug FROM product p JOIN seed s ON s.slug = p.slug
)
-- Admin edits survive: a row an admin changed (version above 0) or reserved above the seed quantity is left alone.
INSERT INTO stock (product_id, on_hand)
SELECT sp.id, s.on_hand FROM seeded_products sp JOIN seed s ON s.slug = sp.slug
ON CONFLICT (product_id) DO UPDATE SET on_hand = EXCLUDED.on_hand
WHERE stock.version = 0 AND stock.reserved <= EXCLUDED.on_hand;
