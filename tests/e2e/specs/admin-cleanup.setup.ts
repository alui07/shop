import { signIn } from './support/auth';
import { deactivateProduct, e2eSlugPrefix, listAdminProducts } from './support/admin';
import { expect, test } from './support/test';

// Runs before every other spec: an e2e product left active by an interrupted run would break the
// public specs that count products. Only slugs with the e2e prefix are ever touched.
test('no e2e product is left active', async ({ page }) => {
  await signIn(page, 'admin', '/admin');

  const leftovers = (await listAdminProducts(page, e2eSlugPrefix)).filter(
    (product) => product.slug.startsWith(e2eSlugPrefix) && product.active,
  );
  for (const product of leftovers) {
    await deactivateProduct(page, product.slug);
  }

  const stillActive = (await listAdminProducts(page, e2eSlugPrefix)).filter(
    (product) => product.slug.startsWith(e2eSlugPrefix) && product.active,
  );
  expect(stillActive.map((product) => product.slug)).toEqual([]);
});
