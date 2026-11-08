"""只识别用户明确的“仅根据《完整标题》”约束，不猜测模糊文章名。"""

import re

_EXCLUSIVE = re.compile(r"(?:只|仅)(?:根据|依据)\s*")
_JOIN = re.compile(r"\s*(?:和|与|及|、|以及)\s*")


def exclusive_titles(question: str) -> tuple[str, ...]:
    match = _EXCLUSIVE.search(question)
    if match is None:
        return ()
    position = match.end()
    titles: list[str] = []
    while position < len(question) and question[position] == "《":
        start = position + 1
        depth = 1
        position = start
        while position < len(question) and depth:
            if question[position] == "《":
                depth += 1
            elif question[position] == "》":
                depth -= 1
            position += 1
        if depth:
            return ()
        title = question[start : position - 1].strip()
        if not title or len(title) > 160:
            return ()
        if title not in titles:
            titles.append(title)
        joined = _JOIN.match(question, position)
        if joined is None:
            break
        position = joined.end()
    return tuple(titles)
