Asset definitions can depend on other asset definitions. The dependent asset is called the **downstream asset**, and the asset it depends on is the **upstream asset**.
You can define a dependency between two assets by passing the upstream asset to the `deps` parameter in the downstream asset's `@asset` decorator.
