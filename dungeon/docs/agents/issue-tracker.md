# Issue tracker

Issues and specifications live in GitHub Issues for
`Dungeon-CampusMinden/Dungeon`.

Use the authenticated `gh` CLI with an explicit `--repo` argument so the
repository's additional remotes cannot affect lookup.

## Read an issue

```powershell
gh issue view <number> --repo Dungeon-CampusMinden/Dungeon --comments
```

A GitHub number can also identify a pull request. If issue lookup fails, read
the pull request instead:

```powershell
gh pr view <number> --repo Dungeon-CampusMinden/Dungeon --comments
```

Pull requests are not treated as feature requests by default.
